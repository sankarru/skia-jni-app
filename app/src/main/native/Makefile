ANDROID_NDK   ?= /opt/android-ndk-r29
API           ?= 21
ARCH          ?= aarch64
SKIA_DIR      ?= $(CURDIR)/../../skia
BUILD_DIR     := $(CURDIR)/build
OUT_DIR       := $(CURDIR)/libs

TARGET        := $(ARCH)-linux-android
TRIPLE        := $(TARGET)$(API)

TOOLCHAIN     := $(ANDROID_NDK)/toolchains/llvm/prebuilt/linux-x86_64/bin
CC            := $(TOOLCHAIN)/$(TRIPLE)-clang
CXX           := $(TOOLCHAIN)/$(TRIPLE)-clang++
AR            := $(TOOLCHAIN)/llvm-ar

CXXFLAGS      := -std=c++17 -fPIC -shared -O2 -DNDEBUG \
                   -I$(SKIA_DIR) \
                   -I$(SKIA_DIR)/include/core \
                   -I$(SKIA_DIR)/include/codec \
                   -I$(SKIA_DIR)/include/config \
                   -I$(SKIA_DIR)/include/private

LDFLAGS       := -L$(BUILD_DIR) -lskia -llog -landroid

SRC           := skia_jni.cpp
SO_NAME       := libskia_jni.so

.PHONY: all clean setup

all: setup $(OUT_DIR)/$(SO_NAME)

setup:
	@mkdir -p $(BUILD_DIR) $(OUT_DIR)

$(BUILD_DIR)/skia_jni.o: $(SRC)
	$(CXX) $(CXXFLAGS) -c $< -o $@

$(OUT_DIR)/$(SO_NAME): $(BUILD_DIR)/skia_jni.o
	$(CXX) -shared -o $@ $< $(LDFLAGS)
	@echo "Built: $@"

clean:
	rm -rf $(BUILD_DIR) $(OUT_DIR)
