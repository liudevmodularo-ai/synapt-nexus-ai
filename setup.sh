#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════
#  Synapt Nexus AI — Project Setup Script
#  Run once after extracting the zip
# ═══════════════════════════════════════════════════════════════
set -e
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'

echo -e "${CYAN}"
echo "  ███████╗██╗   ██╗███╗   ██╗ █████╗ ██████╗ ████████╗"
echo "  ██╔════╝╚██╗ ██╔╝████╗  ██║██╔══██╗██╔══██╗╚══██╔══╝"
echo "  ███████╗ ╚████╔╝ ██╔██╗ ██║███████║██████╔╝   ██║   "
echo "  ╚════██║  ╚██╔╝  ██║╚██╗██║██╔══██║██╔═══╝    ██║   "
echo "  ███████║   ██║   ██║ ╚████║██║  ██║██║        ██║   "
echo "  ╚══════╝   ╚═╝   ╚═╝  ╚═══╝╚═╝  ╚═╝╚═╝        ╚═╝   "
echo -e "${NC}"
echo -e "${CYAN}  NEXUS AI — Setup Script${NC}"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

# ── 1. Check prerequisites ────────────────────────────────────
echo -e "\n${YELLOW}[1/6] Checking prerequisites...${NC}"

check_cmd() {
    command -v "$1" >/dev/null 2>&1 || { echo -e "${RED}❌ $1 not found. Please install it.${NC}"; exit 1; }
    echo -e "  ${GREEN}✅ $1 found${NC}"
}

check_cmd git
check_cmd java

if [ -z "$ANDROID_HOME" ] && [ -z "$ANDROID_SDK_ROOT" ]; then
    echo -e "${RED}❌ ANDROID_HOME or ANDROID_SDK_ROOT not set.${NC}"
    echo "   Please set it to your Android SDK path."
    echo "   Example: export ANDROID_HOME=~/Library/Android/sdk"
    exit 1
fi
echo -e "  ${GREEN}✅ Android SDK: ${ANDROID_HOME:-$ANDROID_SDK_ROOT}${NC}"

# ── 2. llama.cpp submodule ────────────────────────────────────
echo -e "\n${YELLOW}[2/6] Initializing llama.cpp submodule...${NC}"
LLAMA_DIR="app/src/main/cpp/third_party/llama.cpp"

if [ ! -d "$LLAMA_DIR/.git" ]; then
    git init 2>/dev/null || true
    git submodule add https://github.com/ggerganov/llama.cpp "$LLAMA_DIR" 2>/dev/null || \
    git submodule update --init --recursive
    echo -e "  ${GREEN}✅ llama.cpp submodule initialized${NC}"
else
    git submodule update --recursive --remote
    echo -e "  ${GREEN}✅ llama.cpp submodule updated${NC}"
fi

# ── 3. Gradle wrapper ─────────────────────────────────────────
echo -e "\n${YELLOW}[3/6] Setting up Gradle wrapper...${NC}"
if [ ! -f "gradlew" ]; then
    gradle wrapper --gradle-version 8.7 2>/dev/null || {
        echo -e "  ${YELLOW}⚠️  gradle not found locally, downloading wrapper manually...${NC}"
        curl -sL "https://services.gradle.org/distributions/gradle-8.7-bin.zip" -o /tmp/gradle.zip
        mkdir -p gradle/wrapper
        # Download just the wrapper jar from a known source
        curl -sL "https://raw.githubusercontent.com/gradle/gradle/v8.7.0/gradle/wrapper/gradle-wrapper.jar" \
             -o gradle/wrapper/gradle-wrapper.jar 2>/dev/null || true
    }
fi

if [ -f "gradlew" ]; then
    chmod +x gradlew
    echo -e "  ${GREEN}✅ Gradle wrapper ready${NC}"
fi

# ── 4. WireGuard AAR ──────────────────────────────────────────
echo -e "\n${YELLOW}[4/6] Downloading WireGuard tunnel library...${NC}"
mkdir -p app/libs
WG_AAR="app/libs/tunnel.aar"
WG_URL="https://download.wireguard.com/android-library/tunnel-1.0.20230706.aar"

if [ ! -f "$WG_AAR" ]; then
    curl -sL "$WG_URL" -o "$WG_AAR" 2>/dev/null && \
        echo -e "  ${GREEN}✅ WireGuard AAR downloaded${NC}" || \
        echo -e "  ${YELLOW}⚠️  WireGuard AAR download skipped (manual setup needed for Sprint 3)${NC}"
else
    echo -e "  ${GREEN}✅ WireGuard AAR already present${NC}"
fi

# ── 5. Debug keystore ─────────────────────────────────────────
echo -e "\n${YELLOW}[5/6] Setting up debug keystore...${NC}"
mkdir -p keystore
if [ ! -f "keystore/debug.keystore" ]; then
    keytool -genkey -v \
        -keystore keystore/debug.keystore \
        -storepass android -alias androiddebugkey -keypass android \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Android Debug,O=Android,C=US" 2>/dev/null && \
        echo -e "  ${GREEN}✅ Debug keystore created${NC}" || \
        cp "$HOME/.android/debug.keystore" keystore/debug.keystore 2>/dev/null && \
        echo -e "  ${GREEN}✅ Debug keystore copied from ~/.android${NC}"
fi

# ── 6. local.properties ───────────────────────────────────────
echo -e "\n${YELLOW}[6/6] Generating local.properties...${NC}"
if [ ! -f "local.properties" ]; then
    SDK_PATH="${ANDROID_HOME:-$ANDROID_SDK_ROOT}"
    echo "sdk.dir=$SDK_PATH" > local.properties
    echo -e "  ${GREEN}✅ local.properties created with sdk.dir=$SDK_PATH${NC}"
else
    echo -e "  ${GREEN}✅ local.properties already exists${NC}"
fi

# ── Done ──────────────────────────────────────────────────────
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo -e "${GREEN}✅ Setup complete!${NC}"
echo ""
echo -e "Build debug APK:"
echo -e "  ${CYAN}./gradlew assembleDebug${NC}"
echo ""
echo -e "Install on connected device:"
echo -e "  ${CYAN}./gradlew installDebug${NC}"
echo ""
echo -e "Open in Android Studio:"
echo -e "  ${CYAN}File → Open → select this folder${NC}"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
