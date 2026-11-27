// Host-test voor pi_protocol.h:  g++ -std=c++11 -Wall -Wextra -I../spirit_pi_v3 test_protocol.cpp && ./a.out
#include "pi_protocol.h"
#include <cassert>
#include <cstdio>
#include <cstring>
#include <string>

using namespace pi;

static int fails = 0;
#define CHECK(c) do { if (!(c)) { printf("FAIL %s:%d  %s\n", __FILE__, __LINE__, #c); fails++; } } while (0)

static Result run(Settings &s, const char *line) {
  char buf[128];
  strncpy(buf, line, sizeof buf); buf[sizeof buf - 1] = 0;
  return handleLine(s, buf);
}

int main() {
  Settings s; setDefaults(s);
  CHECK(s.pw == 130 && s.d1 == 20 && s.d2 == 60 && s.f == 60 && s.avg == 4);

  CHECK(run(s, "GET").action == ACT_GET);
  CHECK(run(s, "get").action == ACT_GET);
  CHECK(run(s, "PING\r").action == ACT_PING);
  CHECK(run(s, "SAVE").action == ACT_SAVE);
  CHECK(run(s, "   ").action == ACT_NONE);

  CHECK(run(s, "SET pw 80").action == ACT_SET && s.pw == 80);
  Result r = run(s, "SET pw 19");
  CHECK(r.action == ACT_ERROR && strcmp(r.err, "range") == 0 && s.pw == 80);
  r = run(s, "SET pw 8x");
  CHECK(r.action == ACT_ERROR && strcmp(r.err, "value") == 0);
  CHECK(run(s, "SET f 0").action == ACT_ERROR && s.f == 60);   // nooit 0
  CHECK(run(s, "SET f 200").action == ACT_SET && s.f == 200);
  CHECK(run(s, "SET avg 33").action == ACT_ERROR);
  CHECK(run(s, "SET avg 32").action == ACT_SET && s.avg == 32);
  CHECK(run(s, "SET vcal 1.02").action == ACT_SET && s.vcal > 1.019f && s.vcal < 1.021f);
  CHECK(run(s, "SET vcal 1.3").action == ACT_ERROR);
  CHECK(run(s, "SET vcal 1.2").action == ACT_SET);

  // d1/d2-afhankelijkheid
  CHECK(run(s, "SET d2 25").action == ACT_ERROR);           // < d1+10
  CHECK(run(s, "SET d2 30").action == ACT_SET && s.d2 == 30);
  CHECK(run(s, "SET d1 100").action == ACT_SET && s.d1 == 100 && s.d2 == 110);
  CHECK(run(s, "SET d2 301").action == ACT_ERROR);
  CHECK(run(s, "SET d1 7").action == ACT_ERROR);

  CHECK(run(s, "SET legacy 1").action == ACT_SET && s.legacy);
  CHECK(run(s, "SET legacy 2").action == ACT_ERROR);
  r = run(s, "SET name Mijn Detector");
  CHECK(r.action == ACT_SET && r.needsReboot && strcmp(s.name, "Mijn Detector") == 0);
  CHECK(run(s, "SET name a,b").action == ACT_ERROR);
  CHECK(run(s, "SET lowbat 0").action == ACT_SET && s.lowbat == 0.0f);

  CHECK(run(s, "SET").action == ACT_ERROR);
  CHECK(run(s, "SET pw").action == ACT_ERROR);
  CHECK(strcmp(run(s, "SET foo 1").err, "unknown_key") == 0);
  CHECK(strcmp(run(s, "HELLO").err, "unknown") == 0);

  CHECK(run(s, "DEFAULTS").action == ACT_DEFAULTS && s.pw == 130 && s.f == 60 && !s.legacy);
  CHECK(strcmp(s.name, "Mijn Detector") == 0);   // naam blijft

  // legacy
  setDefaults(s);
  applyLegacy(s, 'n'); CHECK(s.pw == 140);
  applyLegacy(s, 'm'); applyLegacy(s, 'm'); CHECK(s.pw == 120);
  applyLegacy(s, 'j'); CHECK(s.f == 70);
  for (int i = 0; i < 20; ++i) applyLegacy(s, 'k');
  CHECK(s.f == F_MIN);   // nooit 0
  applyLegacy(s, 'p'); CHECK(s.f == 60 && s.pw == 130);

  // sanitize
  Settings bad; setDefaults(bad);
  bad.f = 0; bad.pw = 9999; bad.d1 = 100; bad.d2 = 50; bad.vcal = 0.0f / 0.0f; bad.avg = 0; bad.name[0] = 0;
  sanitize(bad);
  CHECK(bad.f == F_MIN && bad.pw == PW_MAX && bad.d2 == 110 && bad.vcal == 1.0f && bad.avg == 1);
  CHECK(strcmp(bad.name, NAME_DEF) == 0);

  // LineReader
  LineReader rd;
  std::string got;
  const char *stream = "nnSET pw 80\nj\r\nGET\n";
  int legacyCount = 0, lines = 0;
  for (const char *p = stream; *p; ++p) {
    LineReader::Kind k = rd.feed(*p);
    if (k == LineReader::LEGACY) legacyCount++;
    if (k == LineReader::LINE) { lines++; got += rd.line(); got += "|"; }
  }
  CHECK(legacyCount == 3);
  CHECK(got == "SET pw 80||GET|");
  for (int i = 0; i < 100; ++i) rd.feed('A');
  CHECK(rd.feed('\n') == LineReader::TOO_LONG);
  CHECK(rd.feed('\n') == LineReader::LINE);

  // format
  char out[200];
  setDefaults(s);
  formatCfg(s, out, sizeof out);
  CHECK(strcmp(out, "CFG,pw=130,d1=20,d2=60,f=60,avg=4,vcal=1.000,lowbat=9.5,legacy=0,name=ESP32_Spirit_PI-3,fw=3.0") == 0);
  formatData(1234, 567, 12.345f, out, sizeof out);
  CHECK(strcmp(out, "D,1234,567,12.35") == 0 || strcmp(out, "D,1234,567,12.34") == 0);
  formatLegacy(s, 567, 12.0f, out, sizeof out);
  CHECK(strcmp(out, "<56/60/13/12.00>") == 0);

  if (fails) { printf("%d test(s) mislukt\n", fails); return 1; }
  printf("Alle firmware-protocoltests OK\n");
  return 0;
}
