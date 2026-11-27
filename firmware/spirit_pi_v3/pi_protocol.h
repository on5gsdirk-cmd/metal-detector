// pi_protocol.h — Spirit PI v3: instellingen + tekstprotocol.
// Bewust zonder Arduino-afhankelijkheden, zodat dit ook op de pc getest kan
// worden (zie firmware/test/). Geen dynamisch geheugen.
#pragma once

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ctype.h>

#define PI_FW_VERSION "3.0.1"

namespace pi {

// ---- grenzen --------------------------------------------------------------
static const int PW_MIN = 20,   PW_MAX = 300,  PW_DEF = 130;
static const int D1_MIN = 8,    D1_MAX = 150,  D1_DEF = 20;
static const int D2_GAP = 10,   D2_MAX = 300,  D2_DEF = 60;   // d2 >= d1 + D2_GAP
static const int FREQ_MIN = 20,    FREQ_MAX = 200,   FREQ_DEF = 60;
static const int AVG_MIN = 1,   AVG_MAX = 32,  AVG_DEF = 4;
static const float VCAL_MIN = 0.8f, VCAL_MAX = 1.2f, VCAL_DEF = 1.0f;
static const float LOWBAT_MIN = 0.0f, LOWBAT_MAX = 14.0f, LOWBAT_DEF = 9.5f; // 0 = uit
// Onderste weerstand van de batterijdeler (kOhm); de bovenste is 100k (R2).
// Origineel 47k: dan zit de ADC boven ~10 V al op zijn maximum. 22k meet tot ~15 V.
static const int RLOW_MIN = 10, RLOW_MAX = 100, RLOW_DEF = 47;
static const int BTNAME_LEN = 31;
static const char *const BTNAME_DEF = "ESP32_Spirit_PI-3";

struct Settings {
  int pw;        // pulsbreedte (µs)
  int d1;        // vroege meetdelay na einde puls (µs)
  int d2;        // late meetdelay na einde puls (µs)
  int f;         // pulsfrequentie (Hz)
  int avg;       // middeling over N pulsen
  float vcal;    // kalibratiefactor batterijspanning
  float lowbat;  // pulsen stoppen onder deze spanning (V), 0 = uit
  int rlow;      // onderste delerweerstand in kOhm
  bool legacy;   // oud <a/b/c/d>-formaat sturen (N.E.C.O-app)
  char name[BTNAME_LEN + 1]; // Bluetooth-naam (actief na herstart)
};

inline void setDefaults(Settings &s) {
  s.pw = PW_DEF; s.d1 = D1_DEF; s.d2 = D2_DEF; s.f = FREQ_DEF; s.avg = AVG_DEF;
  s.vcal = VCAL_DEF; s.lowbat = LOWBAT_DEF; s.rlow = RLOW_DEF; s.legacy = false;
  strncpy(s.name, BTNAME_DEF, BTNAME_LEN); s.name[BTNAME_LEN] = 0;
}

template <typename T> inline T clampv(T v, T lo, T hi) { return v < lo ? lo : (v > hi ? hi : v); }

// Brengt een (bv. uit NVS gelezen) set naar geldige waarden. Garandeert f != 0.
inline void sanitize(Settings &s) {
  s.pw = clampv(s.pw, PW_MIN, PW_MAX);
  s.d1 = clampv(s.d1, D1_MIN, D1_MAX);
  s.d2 = clampv(s.d2, s.d1 + D2_GAP, D2_MAX);
  s.f = clampv(s.f, FREQ_MIN, FREQ_MAX);
  s.avg = clampv(s.avg, AVG_MIN, AVG_MAX);
  if (!(s.vcal >= VCAL_MIN && s.vcal <= VCAL_MAX)) s.vcal = VCAL_DEF; // vangt ook NaN
  if (!(s.lowbat >= LOWBAT_MIN && s.lowbat <= LOWBAT_MAX)) s.lowbat = LOWBAT_DEF;
  s.rlow = clampv(s.rlow, RLOW_MIN, RLOW_MAX);
  s.name[BTNAME_LEN] = 0;
  if (s.name[0] == 0) { strncpy(s.name, BTNAME_DEF, BTNAME_LEN); s.name[BTNAME_LEN] = 0; }
}

// ---- commando's -----------------------------------------------------------
enum Action {
  ACT_NONE = 0,     // lege regel
  ACT_GET,          // stuur CFG (+ INFO)
  ACT_SET,          // instelling gewijzigd -> OK + CFG
  ACT_SAVE,         // opslaan in NVS -> OK
  ACT_DEFAULTS,     // fabrieksinstellingen (en opslaan) -> OK + CFG
  ACT_PING,         // -> OK
  ACT_LEGACY,       // oud één-letter-commando, instelling gewijzigd + direct opslaan
  ACT_ERROR         // -> ERR,<err>
};

struct Result {
  Action action;
  const char *err;      // bij ACT_ERROR
  bool needsReboot;     // bv. naamwijziging
};

inline bool ieq(const char *a, const char *b) {
  while (*a && *b) {
    if (toupper((unsigned char)*a) != toupper((unsigned char)*b)) return false;
    ++a; ++b;
  }
  return *a == 0 && *b == 0;
}

inline bool parseIntStrict(const char *s, int &out) {
  if (!s || !*s) return false;
  char *end = 0;
  long v = strtol(s, &end, 10);
  if (*end != 0) return false;
  out = (int)v;
  return true;
}

inline bool parseFloatStrict(const char *s, float &out) {
  if (!s || !*s) return false;
  char *end = 0;
  float v = strtof(s, &end);
  if (*end != 0 || v != v) return false;
  out = v;
  return true;
}

inline bool isLegacyChar(char c) {
  return c == 'n' || c == 'm' || c == 'j' || c == 'k' || c == 'p';
}

// Oude N.E.C.O-commando's: n/m = pulsbreedte ±10 µs (oude "duty" ±1),
// j/k = frequentie ±10 Hz, p = reset naar defaults (vroeger: freq 0 -> crash).
inline Result applyLegacy(Settings &s, char c) {
  Result r = {ACT_LEGACY, 0, false};
  switch (c) {
    case 'n': s.pw = clampv(s.pw + 10, PW_MIN, PW_MAX); break;
    case 'm': s.pw = clampv(s.pw - 10, PW_MIN, PW_MAX); break;
    case 'j': s.f = clampv(s.f + 10, FREQ_MIN, FREQ_MAX); break;
    case 'k': s.f = clampv(s.f - 10, FREQ_MIN, FREQ_MAX); break;
    case 'p': {
      char keep[BTNAME_LEN + 1];
      memcpy(keep, s.name, sizeof keep);
      bool legacy = s.legacy;
      setDefaults(s);
      memcpy(s.name, keep, sizeof keep);
      s.legacy = legacy;
      break;
    }
    default: r.action = ACT_ERROR; r.err = "unknown"; break;
  }
  return r;
}

// Verwerkt één regel (zonder '\n'). 'line' wordt in-place getokeniseerd.
inline Result handleLine(Settings &s, char *line) {
  Result r = {ACT_NONE, 0, false};
  // trim
  while (*line == ' ' || *line == '\t') ++line;
  size_t n = strlen(line);
  while (n && (line[n - 1] == '\r' || line[n - 1] == ' ' || line[n - 1] == '\t')) line[--n] = 0;
  if (n == 0) return r;

  char *save = 0;
  char *cmd = strtok_r(line, " \t", &save);
  char *key = strtok_r(0, " \t", &save);
  char *val = strtok_r(0, "", &save);
  if (val) { while (*val == ' ' || *val == '\t') ++val; }

  if (ieq(cmd, "GET"))      { r.action = ACT_GET; return r; }
  if (ieq(cmd, "SAVE"))     { r.action = ACT_SAVE; return r; }
  if (ieq(cmd, "PING"))     { r.action = ACT_PING; return r; }
  if (ieq(cmd, "DEFAULTS")) {
    char keep[BTNAME_LEN + 1];
    memcpy(keep, s.name, sizeof keep);
    setDefaults(s);
    memcpy(s.name, keep, sizeof keep); // naam blijft behouden
    r.action = ACT_DEFAULTS; return r;
  }
  if (!ieq(cmd, "SET")) { r.action = ACT_ERROR; r.err = "unknown"; return r; }
  if (!key || !val || !*val) { r.action = ACT_ERROR; r.err = "syntax"; return r; }

  r.action = ACT_SET;
  int iv; float fv;
  if (ieq(key, "pw")) {
    if (!parseIntStrict(val, iv)) goto badval;
    if (iv < PW_MIN || iv > PW_MAX) goto range;
    s.pw = iv;
  } else if (ieq(key, "d1")) {
    if (!parseIntStrict(val, iv)) goto badval;
    if (iv < D1_MIN || iv > D1_MAX) goto range;
    s.d1 = iv;
    if (s.d2 < s.d1 + D2_GAP) s.d2 = s.d1 + D2_GAP; // d2 schuift mee
  } else if (ieq(key, "d2")) {
    if (!parseIntStrict(val, iv)) goto badval;
    if (iv < s.d1 + D2_GAP || iv > D2_MAX) goto range;
    s.d2 = iv;
  } else if (ieq(key, "f")) {
    if (!parseIntStrict(val, iv)) goto badval;
    if (iv < FREQ_MIN || iv > FREQ_MAX) goto range;
    s.f = iv;
  } else if (ieq(key, "avg")) {
    if (!parseIntStrict(val, iv)) goto badval;
    if (iv < AVG_MIN || iv > AVG_MAX) goto range;
    s.avg = iv;
  } else if (ieq(key, "vcal")) {
    if (!parseFloatStrict(val, fv)) goto badval;
    if (fv < VCAL_MIN - 1e-6f || fv > VCAL_MAX + 1e-6f) goto range;
    s.vcal = fv;
  } else if (ieq(key, "lowbat")) {
    if (!parseFloatStrict(val, fv)) goto badval;
    if (fv < LOWBAT_MIN || fv > LOWBAT_MAX) goto range;
    s.lowbat = fv;
  } else if (ieq(key, "rlow")) {
    if (!parseIntStrict(val, iv)) goto badval;
    if (iv < RLOW_MIN || iv > RLOW_MAX) goto range;
    s.rlow = iv;
  } else if (ieq(key, "legacy")) {
    if (!parseIntStrict(val, iv) || (iv != 0 && iv != 1)) goto badval;
    s.legacy = iv == 1;
  } else if (ieq(key, "name")) {
    size_t len = strlen(val);
    if (len == 0 || len > (size_t)BTNAME_LEN) goto range;
    for (size_t i = 0; i < len; ++i)
      if (!isprint((unsigned char)val[i]) || val[i] == ',') goto badval;
    memcpy(s.name, val, len); s.name[len] = 0;
    r.needsReboot = true;
  } else {
    r.action = ACT_ERROR; r.err = "unknown_key"; return r;
  }
  return r;
badval:
  r.action = ACT_ERROR; r.err = "value"; return r;
range:
  r.action = ACT_ERROR; r.err = "range"; return r;
}

// ---- regel-assemblage -----------------------------------------------------
// Bouwt regels uit losse bytes. Oude één-letter-commando's (n m j k p) worden
// alleen herkend als ze aan het begin van een (lege) regel staan, zodat
// "SET pw 80" niet als 'p' gezien wordt.
class LineReader {
 public:
  LineReader() : len_(0), overflow_(false) { buf_[0] = 0; }

  enum Kind { NOTHING, LINE, LEGACY, TOO_LONG };

  // Geeft LINE terug als er een volledige regel in line() staat,
  // LEGACY met legacyChar() bij een oud commando.
  Kind feed(char c) {
    if (c == '\n') {
      buf_[len_] = 0;
      bool ov = overflow_;
      len_ = 0; overflow_ = false;
      return ov ? TOO_LONG : LINE;
    }
    if (len_ == 0 && !overflow_ && isLegacyChar(c)) { legacy_ = c; return LEGACY; }
    if (len_ == 0 && (c == '\r' || c == ' ')) return NOTHING;
    if (len_ < sizeof(buf_) - 1) buf_[len_++] = c; else overflow_ = true;
    return NOTHING;
  }
  char *line() { return buf_; }
  char legacyChar() const { return legacy_; }

 private:
  char buf_[64];
  size_t len_;
  bool overflow_;
  char legacy_ = 0;
};

// ---- uitgaande regels -----------------------------------------------------
inline int formatCfg(const Settings &s, char *out, size_t n) {
  return snprintf(out, n, "CFG,pw=%d,d1=%d,d2=%d,f=%d,avg=%d,vcal=%.3f,lowbat=%.1f,rlow=%d,legacy=%d,name=%s,fw=%s",
                  s.pw, s.d1, s.d2, s.f, s.avg, (double)s.vcal, (double)s.lowbat, s.rlow,
                  s.legacy ? 1 : 0, s.name, PI_FW_VERSION);
}

inline int formatData(int s1, int s2, float vbat, char *out, size_t n) {
  return snprintf(out, n, "D,%d,%d,%.2f", s1, s2, (double)vbat);
}

// Oud formaat: <signaal/freq/duty/volt>, signaal = late sample / 10, duty = pw/10.
inline int formatLegacy(const Settings &s, int s2, float vbat, char *out, size_t n) {
  return snprintf(out, n, "<%d/%d/%d/%.2f>", s2 / 10, s.f, s.pw / 10, (double)vbat);
}

}  // namespace pi
