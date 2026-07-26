// ESP32_Piano.ino  - 改良版：立即送出按鍵事件（使用 SerialBT.write） + OLED 顯示
#include "BluetoothSerial.h"
#include <Wire.h>
#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>

BluetoothSerial SerialBT;

// === OLED 設定 ===
#define SCREEN_WIDTH 128
#define SCREEN_HEIGHT 32
Adafruit_SSD1306 display(SCREEN_WIDTH, SCREEN_HEIGHT, &Wire, -1);

// === 腳位定義 ===
const int LED_PINS[7] = {2, 4, 16, 17, 5, 18, 19};
const int BUTTON_PINS[7] = {15, 13, 12, 14, 27, 26, 25};
const int BTN_BLACK_PIN = 32;
const int BTN_OCT_UP_PIN = 33;
const int BTN_OCT_DOWN_PIN = 21;

const unsigned long DEBOUNCE_MS = 50; // 可視情況調整為 20~30ms

// === 狀態變數 ===
bool blackPressed = false;
int octaveFlag = 0;  // -1, 0, 1

// 每顆 LED 的亮度與模式
int ledBrightness[7] = {0};   // 0=滅, 40=黑鍵暗亮, 255=白鍵全亮
int ledMode[7] = {0};         // 0=白鍵模式, 1=黑鍵模式

// Debounce 變數
int lastReading[7];
int stableState[7];
unsigned long lastDebounceTime[7];

int lastBlackReading = HIGH;
int stableBlack = HIGH;
unsigned long lastBlackDebounce = 0;

int lastOctUpReading = HIGH;
int stableOctUp = HIGH;
unsigned long lastOctUpDebounce = 0;

int lastOctDownReading = HIGH;
int stableOctDown = HIGH;
unsigned long lastOctDownDebounce = 0;

// Debug 開關（上線時可設為 false）
const bool DEBUG_SERIAL = false;

// === 輔助函式 ===
int clampOctave(int v) {
  if (v < -1) return -1;
  if (v > 1) return 1;
  return v;
}

void setLed(int idx, int brightness, int mode) {
  if (idx < 0 || idx > 6) return;
  analogWrite(LED_PINS[idx], brightness);
  ledBrightness[idx] = brightness;
  ledMode[idx] = mode;
}

void allOff() {
  for (int i = 0; i < 7; ++i) {
    analogWrite(LED_PINS[i], 0);
    ledBrightness[i] = 0;
    ledMode[i] = 0;
  }
}

// 一次性寫入字串到藍牙（非阻塞式呼叫）
void btWriteString(const char* s) {
  if (!SerialBT.hasClient()) return;
  size_t len = strlen(s);
  if (len == 0) return;
  SerialBT.write((const uint8_t*)s, (size_t)len);
}

// === OLED 更新函式（新增） ===
void updateOctaveDisplay() {
  int octaveNumber;
  if (octaveFlag == -1) octaveNumber = 3;
  else if (octaveFlag == 0) octaveNumber = 4;
  else octaveNumber = 5;

  // 清畫面並顯示
  display.clearDisplay();
  display.setTextSize(2);
  display.setTextColor(SSD1306_WHITE);
  display.setCursor(0, 0);
  display.print("Octave:");
  display.println(octaveNumber);

  display.setTextSize(1);
  display.setCursor(0, 24);
  if (blackPressed) display.print("Black mode: ON");
  else display.print("Black mode: OFF");

  display.display();
}

// === 手機指令處理（改為一次性 write） ===
void handleIncomingChar(char c) {
  char buf[48];
  if (c >= '1' && c <= '7') {
    int idx = c - '1';
    if (blackPressed) {
      setLed(idx, 40, 1);
    } else {
      setLed(idx, 255, 0);
    }
    // 組成 "ONn\n"
    int n = snprintf(buf, sizeof(buf), "ON%d\n", idx + 1);
    if (n > 0) SerialBT.write((const uint8_t*)buf, (size_t)n);

  } else if (c == '0') {
    allOff();
    int n = snprintf(buf, sizeof(buf), "OFF\n");
    if (n > 0) SerialBT.write((const uint8_t*)buf, (size_t)n);

  } else if (c == 'b') {
    blackPressed = true;
    // 更新 OLED 顯示黑鍵狀態
    updateOctaveDisplay();
    int n = snprintf(buf, sizeof(buf), "ONBLACK\n");
    if (n > 0) SerialBT.write((const uint8_t*)buf, (size_t)n);

  } else if (c == 'B') {
    blackPressed = false;
    // 更新 OLED 顯示黑鍵狀態
    updateOctaveDisplay();
    int n = snprintf(buf, sizeof(buf), "OFFBLACK\n");
    if (n > 0) SerialBT.write((const uint8_t*)buf, (size_t)n);

  } else if (c == 'u') {
    if (octaveFlag < 1) octaveFlag = clampOctave(octaveFlag + 1);
    // 更新 OLED 顯示八度
    updateOctaveDisplay();
    int n = snprintf(buf, sizeof(buf), "Oct%d\n", octaveFlag);
    if (n > 0) SerialBT.write((const uint8_t*)buf, (size_t)n);

  } else if (c == 'd') {
    if (octaveFlag > -1) octaveFlag = clampOctave(octaveFlag - 1);
    // 更新 OLED 顯示八度
    updateOctaveDisplay();
    int n = snprintf(buf, sizeof(buf), "Oct%d\n", octaveFlag);
    if (n > 0) SerialBT.write((const uint8_t*)buf, (size_t)n);

  } else {
    // 未知指令，回傳 RX:char\n（一次性）
    int n = snprintf(buf, sizeof(buf), "RX:%c\n", c);
    if (n > 0) SerialBT.write((const uint8_t*)buf, (size_t)n);
  }
}

// === Setup ===
void setup() {
  if (DEBUG_SERIAL) Serial.begin(115200);
  SerialBT.begin("ESP32_PIANO");

  // OLED 初始化 (SDA=23, SCL=22) -> 避免與按鍵 21 衝突
  Wire.begin(23, 22);
  if(!display.begin(SSD1306_SWITCHCAPVCC, 0x3C)) {
    if (DEBUG_SERIAL) Serial.println(F("SSD1306 allocation failed"));
    // 若 OLED 初始化失敗，仍繼續執行其餘功能
  } else {
    updateOctaveDisplay(); // 初始顯示八度
  }

  // 啟用 LED 腳位 PWM（保留你原本的 analogWrite 呼叫）
  for (int i = 0; i < 7; ++i) {
    pinMode(LED_PINS[i], OUTPUT);
    analogWrite(LED_PINS[i], 0);
  }

  // 按鍵腳位
  for (int i = 0; i < 7; ++i) {
    pinMode(BUTTON_PINS[i], INPUT_PULLUP);
    lastReading[i] = digitalRead(BUTTON_PINS[i]);
    stableState[i] = lastReading[i];
    lastDebounceTime[i] = 0;
  }

  pinMode(BTN_BLACK_PIN, INPUT_PULLUP);
  pinMode(BTN_OCT_UP_PIN, INPUT_PULLUP);
  pinMode(BTN_OCT_DOWN_PIN, INPUT_PULLUP);

  lastBlackReading = digitalRead(BTN_BLACK_PIN);
  stableBlack = lastBlackReading;
  lastOctUpReading = digitalRead(BTN_OCT_UP_PIN);
  stableOctUp = lastOctUpReading;
  lastOctDownReading = digitalRead(BTN_OCT_DOWN_PIN);
  stableOctDown = lastOctDownReading;

  allOff();
  if (DEBUG_SERIAL) Serial.println("ESP32_PIANO Ready (improved BT write + OLED)");
}

// === Main Loop ===
void loop() {
  // 1. 處理手機傳來的指令（一次讀一個字元並立即處理）
  while (SerialBT.available()) {
    char c = (char)SerialBT.read();
    handleIncomingChar(c);
  }

  // 2. 實體黑鍵按鈕 (debounce)
  int br = digitalRead(BTN_BLACK_PIN);
  if (br != lastBlackReading) lastBlackDebounce = millis();
  if ((millis() - lastBlackDebounce) > DEBOUNCE_MS) {
    if (stableBlack != br) {
      stableBlack = br;
      if (stableBlack == LOW) {
        blackPressed = true;
        // 更新 OLED 顯示黑鍵狀態
        updateOctaveDisplay();
        if (SerialBT.hasClient()) {
          const char* s = "ONBLACK\n";
          SerialBT.write((const uint8_t*)s, strlen(s));
        }
      } else {
        blackPressed = false;
        // 更新 OLED 顯示黑鍵狀態
        updateOctaveDisplay();
        if (SerialBT.hasClient()) {
          const char* s = "OFFBLACK\n";
          SerialBT.write((const uint8_t*)s, strlen(s));
        }
      }
    }
  }
  lastBlackReading = br;

  // 3. 八度上 (實體)
  int ur = digitalRead(BTN_OCT_UP_PIN);
  if (ur != lastOctUpReading) lastOctUpDebounce = millis();
  if ((millis() - lastOctUpDebounce) > DEBOUNCE_MS) {
    if (stableOctUp != ur && stableOctUp == HIGH && ur == LOW) {
      if (octaveFlag < 1) {
        octaveFlag = clampOctave(octaveFlag + 1);
        // 更新 OLED 顯示八度
        updateOctaveDisplay();
        if (SerialBT.hasClient()) {
          char buf[24];
          int n = snprintf(buf, sizeof(buf), "Oct%d\n", octaveFlag);
          if (n > 0) SerialBT.write((const uint8_t*)buf, (size_t)n);
        }
      }
    }
    stableOctUp = ur;
  }
  lastOctUpReading = ur;

  // 4. 八度下 (實體)
  int dr = digitalRead(BTN_OCT_DOWN_PIN);
  if (dr != lastOctDownReading) lastOctDownDebounce = millis();
  if ((millis() - lastOctDownDebounce) > DEBOUNCE_MS) {
    if (stableOctDown != dr && stableOctDown == HIGH && dr == LOW) {
      if (octaveFlag > -1) {
        octaveFlag = clampOctave(octaveFlag - 1);
        // 更新 OLED 顯示八度
        updateOctaveDisplay();
        if (SerialBT.hasClient()) {
          char buf[24];
          int n = snprintf(buf, sizeof(buf), "Oct%d\n", octaveFlag);
          if (n > 0) SerialBT.write((const uint8_t*)buf, (size_t)n);
        }
      }
    }
    stableOctDown = dr;
  }
  lastOctDownReading = dr;

  // 5. 七個實體白鍵按鈕
  for (int i = 0; i < 7; ++i) {
    int reading = digitalRead(BUTTON_PINS[i]);
    if (reading != lastReading[i]) {
      lastDebounceTime[i] = millis();
    }

    if ((millis() - lastDebounceTime[i]) > DEBOUNCE_MS) {
      if (stableState[i] == HIGH && reading == LOW) {  // 確定按下
        // 立即組訊息並送出（一次 write）
        int bflag = blackPressed ? 1 : 0;
        int oflag = octaveFlag;
        char buf[48];
        int n = snprintf(buf, sizeof(buf), "PRESSED%d;B:%d;O:%d\n", i + 1, bflag, oflag);
        if (n > 0 && SerialBT.hasClient()) {
          SerialBT.write((const uint8_t*)buf, (size_t)n);
        }
        if (DEBUG_SERIAL) {
          Serial.print("Sent: ");
          Serial.println(buf);
        }

        // 熄燈邏輯（獨立）
        bool shouldTurnOff = false;
        if (blackPressed && ledMode[i] == 1 && ledBrightness[i] == 40) {
          shouldTurnOff = true;
        } else if (!blackPressed && ledMode[i] == 0 && ledBrightness[i] == 255) {
          shouldTurnOff = true;
        }
        if (shouldTurnOff) {
          setLed(i, 0, 0);
        }
      }
      stableState[i] = reading;
    }
    lastReading[i] = reading;
  }

  // 不要用長 delay，短暫 yield
  delay(2);
}
