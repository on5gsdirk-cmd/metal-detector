// Spirit PI v3 — firmware voor de Spirit PI 2.0 ESP32-print (DOIT ESP32 DevKit V1)
//
// Pulse-inductie: GPIO13 hoog = spoel aan (via BC547/BC557 -> IRF740).
// Per puls twee samples van de opamp-uitgang (GPIO36/VP): op d1 en d2 µs na
// het einde van de puls. Alles instelbaar via Bluetooth Classic (SPP) of USB
// serieel met een eenvoudig tekstprotocol, zie README.md.
//
// Werkt met arduino-esp32 core 2.0.x (ESP-IDF 4.4) en 3.x (ESP-IDF 5).

#include <Arduino.h>
#include <BluetoothSerial.h>
#include <Preferences.h>
#include <esp_timer.h>
#include <soc/gpio_reg.h>
#include "pi_protocol.h"

#if !defined(CONFIG_BT_ENABLED) || !defined(CONFIG_BLUEDROID_ENABLED)
#error Bluetooth staat niet aan in deze core/board-configuratie.
#endif

#if ESP_ARDUINO_VERSION_MAJOR >= 3
  #include <esp_adc/adc_oneshot.h>
  #define PI_ADC_IDF5 1
#else
  #include <driver/adc.h>
  #define PI_ADC_IDF5 0
#endif

// ---- hardware -------------------------------------------------------------
static const int PULSE_PIN = 13;   // naar BC547 (R3)
// Signaal: GPIO36 = VP = ADC1 kanaal 0. Batterij: GPIO35 = ADC1 kanaal 7.
// Batterijdeler op de print: 100k naar +12 V, 47k naar massa.
static const float BAT_R_TOP = 100000.0f;
static const float BAT_R_BOTTOM = 47000.0f;
static const float ADC_REF = 3.6f;   // zoals in de originele sketch; fijnregelen met vcal

// ---- instellingen -----------------------------------------------------------
static pi::Settings g_cfg;          // gewijzigd door de loop-taak
static portMUX_TYPE g_mux = portMUX_INITIALIZER_UNLOCKED;
static Preferences g_prefs;
static BluetoothSerial SerialBT;

// Kopie van de meetparameters voor de meettaak (atomair overgenomen).
struct MeasParams { int pw, d1, d2, f, avg; float vcal, lowbat; };
static volatile bool g_paramsDirty = true;

// ---- meetresultaten (meettaak -> loop) --------------------------------------
static const int RING = pi::AVG_MAX;
static int32_t g_ring1[RING], g_ring2[RING];
static int g_ringPos = 0, g_ringFill = 0;
static uint32_t g_pulseCount = 0;   // verhoogt per puls
static float g_vbat = 0.0f;
static bool g_lowbat = false;
// werkelijke timing (gemeten), µs
static float g_adcUs = 0, g_t1Us = 0, g_t2Us = 0, g_pwUs = 0;
static bool g_split = false;   // om beurten s1/s2 meten (zie measureTask)

// ---- ADC ------------------------------------------------------------------
#if PI_ADC_IDF5
static adc_oneshot_unit_handle_t g_adc = nullptr;
static void adcInit() {
  adc_oneshot_unit_init_cfg_t ucfg = {};
  ucfg.unit_id = ADC_UNIT_1;
  ucfg.ulp_mode = ADC_ULP_MODE_DISABLE;
  ESP_ERROR_CHECK(adc_oneshot_new_unit(&ucfg, &g_adc));
  adc_oneshot_chan_cfg_t ccfg = {};
  ccfg.atten = (adc_atten_t)3;           // 11/12 dB (zelfde waarde in IDF 5.x)
  ccfg.bitwidth = ADC_BITWIDTH_12;
  ESP_ERROR_CHECK(adc_oneshot_config_channel(g_adc, ADC_CHANNEL_0, &ccfg));
  ESP_ERROR_CHECK(adc_oneshot_config_channel(g_adc, ADC_CHANNEL_7, &ccfg));
}
static inline int adcSignal() { int v = 0; adc_oneshot_read(g_adc, ADC_CHANNEL_0, &v); return v; }
static inline int adcBattery() { int v = 0; adc_oneshot_read(g_adc, ADC_CHANNEL_7, &v); return v; }
#else
static void adcInit() {
  adc1_config_width(ADC_WIDTH_BIT_12);
  adc1_config_channel_atten(ADC1_CHANNEL_0, (adc_atten_t)3);  // 11 dB
  adc1_config_channel_atten(ADC1_CHANNEL_7, (adc_atten_t)3);  // 11 dB
}
static inline int adcSignal() { return adc1_get_raw(ADC1_CHANNEL_0); }
static inline int adcBattery() { return adc1_get_raw(ADC1_CHANNEL_7); }
#endif

static inline void pulseHigh() { REG_WRITE(GPIO_OUT_W1TS_REG, 1UL << PULSE_PIN); }
static inline void pulseLow()  { REG_WRITE(GPIO_OUT_W1TC_REG, 1UL << PULSE_PIN); }
static inline void waitUntil(int64_t t) { while (esp_timer_get_time() < t) { } }

// ---- meettaak -----------------------------------------------------------------
// Draait op core 1 met hoge prioriteit. De Bluetooth-stack draait op core 0, en
// er wordt nooit gezonden midden in een meetcyclus: zenden gebeurt in loop()
// (lage prioriteit), die pas aan de beurt komt als deze taak wacht.
static TaskHandle_t g_measTask = nullptr;
static esp_timer_handle_t g_pulseTimer = nullptr;

static void pulseTimerCb(void *) {
  if (g_measTask) xTaskNotifyGive(g_measTask);
}

static float readBatteryVolts(float vcal) {
  int32_t sum = 0;
  for (int i = 0; i < 16; ++i) sum += adcBattery();
  float vpin = (sum / 16.0f) * ADC_REF / 4095.0f;
  float v = vpin * (BAT_R_TOP + BAT_R_BOTTOM) / BAT_R_BOTTOM * vcal;
  return v < 0.9f ? 0.0f : v;
}

static void measureTask(void *) {
  MeasParams p = {};
  int64_t lastBat = 0;
  float vbatFilt = -1;
  float t1Acc = 0, t2Acc = 0, pwAcc = 0; int tN = 0, t1N = 0, t2N = 0;
  int splitPhase = 0, pendingS1 = 0;

  for (;;) {
    ulTaskNotifyTake(pdTRUE, portMAX_DELAY);

    if (g_paramsDirty) {
      portENTER_CRITICAL(&g_mux);
      p = {g_cfg.pw, g_cfg.d1, g_cfg.d2, g_cfg.f, g_cfg.avg, g_cfg.vcal, g_cfg.lowbat};
      g_paramsDirty = false;
      portEXIT_CRITICAL(&g_mux);
      esp_timer_stop(g_pulseTimer);
      esp_timer_start_periodic(g_pulseTimer, 1000000ULL / (uint64_t)p.f);
      portENTER_CRITICAL(&g_mux);
      g_ringFill = 0; g_ringPos = 0;    // middeling opnieuw starten
      splitPhase = 0;
      portEXIT_CRITICAL(&g_mux);
    }

    int64_t now = esp_timer_get_time();

    // Batterij elke 500 ms (tussen twee pulsen, nooit tijdens een meting).
    if (now - lastBat > 500000 || vbatFilt < 0) {
      float v = readBatteryVolts(p.vcal);
      vbatFilt = vbatFilt < 0 ? v : vbatFilt * 0.7f + v * 0.3f;
      bool low = g_lowbat;
      if (p.lowbat > 0) {
        if (!low && vbatFilt < p.lowbat) low = true;
        else if (low && vbatFilt > p.lowbat + 0.3f) low = false;   // hysterese
      } else {
        low = false;
      }
      portENTER_CRITICAL(&g_mux);
      g_vbat = vbatFilt; g_lowbat = low;
      portEXIT_CRITICAL(&g_mux);
      lastBat = now;
    }

    if (g_lowbat) { pulseLow(); continue; }

    // Past er geen volledige ADC-conversie tussen d1 en d2 (core 3.x: ~40 µs),
    // dan om beurten meten: s1 bij de ene puls, s2 bij de volgende. Zo valt
    // elke sample precies op d1 resp. d2 i.p.v. te laat.
    bool split = (float)(p.d2 - p.d1) < g_adcUs + 4.0f;
    if (!split) splitPhase = 0;

    // --- één puls + sample(s), tijdkritisch ---
    int64_t tOn = esp_timer_get_time();
    pulseHigh();
    waitUntil(tOn + p.pw);
    pulseLow();
    int64_t tOff = esp_timer_get_time();
    int s1 = -1, s2 = -1;
    int64_t ts1 = 0, ts2 = 0, tEnd = 0;
    if (!split || splitPhase == 0) {
      waitUntil(tOff + p.d1);
      ts1 = esp_timer_get_time();
      s1 = adcSignal();
      tEnd = esp_timer_get_time();
    }
    if (!split || splitPhase == 1) {
      waitUntil(tOff + p.d2);
      ts2 = esp_timer_get_time();
      s2 = adcSignal();
      tEnd = esp_timer_get_time();
    }
    // --- einde tijdkritisch deel ---

    pwAcc += (float)(tOff - tOn);
    if (s1 >= 0) { t1Acc += (float)(ts1 - tOff); t1N++; }
    if (s2 >= 0) { t2Acc += (float)(ts2 - tOff); t2N++; }
    tN++;
    float conv = (float)(tEnd - (s2 >= 0 ? ts2 : ts1));

    if (split && splitPhase == 0) {       // eerste helft van een paar: s1 bewaren
      pendingS1 = s1;
      splitPhase = 1;
      portENTER_CRITICAL(&g_mux);
      g_adcUs = g_adcUs * 0.95f + 0.05f * conv;
      portEXIT_CRITICAL(&g_mux);
      continue;
    }
    if (split) { s1 = pendingS1; splitPhase = 0; }

    portENTER_CRITICAL(&g_mux);
    g_ring1[g_ringPos] = s1;
    g_ring2[g_ringPos] = s2;
    g_ringPos = (g_ringPos + 1) % p.avg;
    if (g_ringFill < p.avg) g_ringFill++;
    g_pulseCount++;
    g_split = split;
    g_adcUs = g_adcUs * 0.95f + 0.05f * conv;
    if (tN >= 64) {
      g_pwUs = pwAcc / tN;
      g_t1Us = t1N ? t1Acc / t1N : 0;
      g_t2Us = t2N ? t2Acc / t2N : 0;
      pwAcc = t1Acc = t2Acc = 0; tN = t1N = t2N = 0;
    }
    portEXIT_CRITICAL(&g_mux);
  }
}

// ---- uitvoer --------------------------------------------------------------------
static void sendLine(const char *s) {
  Serial.println(s);
  if (SerialBT.hasClient()) SerialBT.println(s);
}

static void sendCfg() {
  char buf[200];
  portENTER_CRITICAL(&g_mux);
  pi::Settings c = g_cfg;
  float adc = g_adcUs, t1 = g_t1Us, t2 = g_t2Us, pw = g_pwUs; bool split = g_split;
  portEXIT_CRITICAL(&g_mux);
  pi::formatCfg(c, buf, sizeof buf);
  sendLine(buf);
  snprintf(buf, sizeof buf, "INFO,adc_us=%.1f,pw_us=%.1f,t1_us=%.1f,t2_us=%.1f,split=%d", adc, pw, t1, t2, split ? 1 : 0);
  sendLine(buf);
}

// ---- NVS --------------------------------------------------------------------------
static void loadSettings() {
  pi::setDefaults(g_cfg);
  g_prefs.begin("spiritpi", true);
  g_cfg.pw = g_prefs.getInt("pw", g_cfg.pw);
  g_cfg.d1 = g_prefs.getInt("d1", g_cfg.d1);
  g_cfg.d2 = g_prefs.getInt("d2", g_cfg.d2);
  g_cfg.f = g_prefs.getInt("f", g_cfg.f);
  g_cfg.avg = g_prefs.getInt("avg", g_cfg.avg);
  g_cfg.vcal = g_prefs.getFloat("vcal", g_cfg.vcal);
  g_cfg.lowbat = g_prefs.getFloat("lowbat", g_cfg.lowbat);
  g_cfg.legacy = g_prefs.getBool("legacy", g_cfg.legacy);
  if (g_prefs.isKey("name")) g_prefs.getString("name", g_cfg.name, sizeof g_cfg.name);
  g_prefs.end();
  pi::sanitize(g_cfg);
}

static void saveSettings() {
  portENTER_CRITICAL(&g_mux);
  pi::Settings c = g_cfg;
  portEXIT_CRITICAL(&g_mux);
  g_prefs.begin("spiritpi", false);
  g_prefs.putInt("pw", c.pw);
  g_prefs.putInt("d1", c.d1);
  g_prefs.putInt("d2", c.d2);
  g_prefs.putInt("f", c.f);
  g_prefs.putInt("avg", c.avg);
  g_prefs.putFloat("vcal", c.vcal);
  g_prefs.putFloat("lowbat", c.lowbat);
  g_prefs.putBool("legacy", c.legacy);
  g_prefs.putString("name", c.name);
  g_prefs.end();
}

// ---- commando's --------------------------------------------------------------------
static void handleResult(const pi::Result &r) {
  char buf[48];
  switch (r.action) {
    case pi::ACT_NONE: break;
    case pi::ACT_GET: sendCfg(); break;
    case pi::ACT_PING: sendLine("OK"); break;
    case pi::ACT_SAVE: saveSettings(); sendLine("OK"); break;
    case pi::ACT_DEFAULTS:
      g_paramsDirty = true; saveSettings(); sendLine("OK"); sendCfg(); break;
    case pi::ACT_SET:
      g_paramsDirty = true;
      sendLine(r.needsReboot ? "OK,reboot" : "OK");
      sendCfg();
      break;
    case pi::ACT_LEGACY:
      g_paramsDirty = true; saveSettings(); break;   // zoals de oude firmware: direct opslaan
    case pi::ACT_ERROR:
      snprintf(buf, sizeof buf, "ERR,%s", r.err ? r.err : "?");
      sendLine(buf);
      break;
  }
}

static void processChar(pi::LineReader &rd, char c) {
  switch (rd.feed(c)) {
    case pi::LineReader::LINE: {
      // instellingen wijzigen onder lock, antwoorden daarbuiten
      pi::Settings tmp;
      portENTER_CRITICAL(&g_mux); tmp = g_cfg; portEXIT_CRITICAL(&g_mux);
      pi::Result r = pi::handleLine(tmp, rd.line());
      portENTER_CRITICAL(&g_mux); g_cfg = tmp; portEXIT_CRITICAL(&g_mux);
      handleResult(r);
      break;
    }
    case pi::LineReader::LEGACY: {
      pi::Settings tmp;
      portENTER_CRITICAL(&g_mux); tmp = g_cfg; portEXIT_CRITICAL(&g_mux);
      pi::Result r = pi::applyLegacy(tmp, rd.legacyChar());
      portENTER_CRITICAL(&g_mux); g_cfg = tmp; portEXIT_CRITICAL(&g_mux);
      handleResult(r);
      break;
    }
    case pi::LineReader::TOO_LONG: sendLine("ERR,too_long"); break;
    default: break;
  }
}

// ---- Bluetooth-status -------------------------------------------------------------------
static volatile bool g_btConnected = false;
static void btCallback(esp_spp_cb_event_t event, esp_spp_cb_param_t *) {
  // Geen ESP.restart() meer bij disconnect: gewoon wachten op de volgende verbinding.
  if (event == ESP_SPP_SRV_OPEN_EVT) g_btConnected = true;
  else if (event == ESP_SPP_CLOSE_EVT) g_btConnected = false;
}

// ---- setup / loop -----------------------------------------------------------------------------
void setup() {
  pinMode(PULSE_PIN, OUTPUT);
  pulseLow();
  Serial.begin(115200);
  loadSettings();
  adcInit();
  {  // ADC-conversietijd meten, zodat de meettaak meteen de juiste modus kiest
    int64_t t0 = esp_timer_get_time();
    for (int i = 0; i < 32; ++i) (void)adcSignal();
    g_adcUs = (float)(esp_timer_get_time() - t0) / 32.0f;
  }

  SerialBT.register_callback(btCallback);
  SerialBT.begin(g_cfg.name);

  esp_timer_create_args_t targs = {};
  targs.callback = &pulseTimerCb;
  targs.name = "pulse";
  ESP_ERROR_CHECK(esp_timer_create(&targs, &g_pulseTimer));

  xTaskCreatePinnedToCore(measureTask, "measure", 4096, nullptr,
                          configMAX_PRIORITIES - 2, &g_measTask, 1);
  esp_timer_start_periodic(g_pulseTimer, 1000000ULL / (uint64_t)g_cfg.f);

  Serial.printf("Spirit PI v%s, Bluetooth-naam: %s\n", PI_FW_VERSION, g_cfg.name);
  sendCfg();
}

static pi::LineReader g_rdSerial, g_rdBt;
static uint32_t g_lastSentPulse = 0;
static uint32_t g_lastData = 0, g_lastLowbatMsg = 0, g_lastInfo = 0;
static bool g_wasConnected = false;

void loop() {
  while (Serial.available() > 0) processChar(g_rdSerial, (char)Serial.read());
  while (SerialBT.available() > 0) processChar(g_rdBt, (char)SerialBT.read());

  if (g_btConnected && !g_wasConnected) { Serial.println("Client verbonden"); sendCfg(); }
  if (!g_btConnected && g_wasConnected) Serial.println("Client verbroken, wacht op nieuwe verbinding");
  g_wasConnected = g_btConnected;

  uint32_t nowMs = millis();

  // Data aan ~28 Hz (elke 36 ms), lopend gemiddelde over de laatste 'avg' pulsen.
  if (nowMs - g_lastData >= 36) {
    g_lastData = nowMs;
    int32_t sum1 = 0, sum2 = 0; int n; uint32_t pc; float vbat; bool low; pi::Settings c;
    portENTER_CRITICAL(&g_mux);
    n = g_ringFill;
    for (int i = 0; i < n; ++i) { sum1 += g_ring1[i]; sum2 += g_ring2[i]; }
    pc = g_pulseCount; vbat = g_vbat; low = g_lowbat; c = g_cfg;
    portEXIT_CRITICAL(&g_mux);

    char buf[64];
    if (low) {
      // geen pulsen: alleen spanning tonen (5 Hz) + af en toe melding
      if (nowMs - g_lastLowbatMsg >= 2000) { g_lastLowbatMsg = nowMs; sendLine("ERR,lowbat"); }
      static uint32_t lastLowD = 0;
      if (nowMs - lastLowD >= 200) {
        lastLowD = nowMs;
        if (c.legacy) pi::formatLegacy(c, 0, vbat, buf, sizeof buf);
        else pi::formatData(0, 0, vbat, buf, sizeof buf);
        sendLine(buf);
      }
    } else if (n > 0 && pc != g_lastSentPulse) {   // alleen als er nieuwe pulsen zijn
      g_lastSentPulse = pc;
      int s1 = (sum1 + n / 2) / n, s2 = (sum2 + n / 2) / n;
      if (c.legacy) pi::formatLegacy(c, s2, vbat, buf, sizeof buf);
      else pi::formatData(s1, s2, vbat, buf, sizeof buf);
      sendLine(buf);
    }
  }

  // Elke 10 s gemeten timing op de debugpoort.
  if (nowMs - g_lastInfo >= 10000) {
    g_lastInfo = nowMs;
    char buf[100];
    portENTER_CRITICAL(&g_mux);
    float adc = g_adcUs, t1 = g_t1Us, t2 = g_t2Us, pw = g_pwUs; bool split = g_split;
    portEXIT_CRITICAL(&g_mux);
    snprintf(buf, sizeof buf, "INFO,adc_us=%.1f,pw_us=%.1f,t1_us=%.1f,t2_us=%.1f,split=%d", adc, pw, t1, t2, split ? 1 : 0);
    Serial.println(buf);
  }

  delay(2);
}
