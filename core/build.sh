#!/usr/bin/env bash
# Builds the IYKYK core (sing-box libbox + Xray + app glue) as an Android .aar.
#
#   ./build.sh deps      pin core versions into go.mod / go.sum (needs network)
#   ./build.sh android   build ../android/app/libs/iykyk-core.aar
#   ./build.sh windows   build build/windows/iykyk-service.exe (Windows x64 core service)
#
# Env: ANDROID_HOME and ANDROID_NDK_HOME must point to the SDK and NDK.
#      ANDROID_TARGETS overrides ABIs (default: arm64 + x86_64 for the emulator).
set -euo pipefail
cd "$(dirname "$0")"

# ---- CORE_VERSIONS ---------------------------------------------------------
SING_BOX_VERSION="v1.14.4"
XRAY_VERSION="v26.10.31"
XRAY_COMMIT="a4a3dff5b20c2faae567b742c172c7b95e75c083" # tag v26.10.31
MIERU_VERSION="v3.39.0"
AMNEZIAWG_VERSION="v3.1.20260828"
GOMOBILE_VERSION="v0.1.13"
# ----------------------------------------------------------------------------

# The official build works only with these subscription domains (subdomains
# included). Override with ALLOWED_SUBSCRIPTION_HOSTS="" for an unlocked build.
ALLOWED_SUBSCRIPTION_HOSTS="${ALLOWED_SUBSCRIPTION_HOSTS-dlyasvoih.xyz}"

CORE_VERSION="${CORE_VERSION:-$(git describe --tags --always --dirty 2>/dev/null || echo dev)}"
ANDROID_TARGETS="${ANDROID_TARGETS:-android/arm64,android/amd64}"
ANDROID_API=26
OUTPUT="../android/app/libs/iykyk-core.aar"

# Same feature set as the official sing-box Android build, minus Tailscale,
# OpenVPN, OpenConnect and USB/IP, which IYKYK does not need.
TAGS=(
    with_gvisor
    with_quic
    with_wireguard
    with_utls
    with_naive_outbound
    with_clash_api
    badlinkname
    tfogo_checklinkname0
)

LDFLAGS=(
    "-X github.com/sagernet/sing-box/constant.Version=${SING_BOX_VERSION#v}"
    "-X github.com/iykyk-dev/iykyk/core/iykyk.CoreVersion=${CORE_VERSION}"
    "-X github.com/iykyk-dev/iykyk/core/iykyk.allowedHosts=${ALLOWED_SUBSCRIPTION_HOSTS}"
    "-X runtime.godebugDefault=multipathtcp=0,tlssha1=1"
    "-checklinkname=0"
    "-s" "-w" "-buildid="
)

deps() {
    go get \
        "github.com/sagernet/sing-box@${SING_BOX_VERSION}" \
        "github.com/xtls/xray-core@${XRAY_COMMIT}" \
        "github.com/enfein/mieru/v3@${MIERU_VERSION}" \
        "github.com/amnezia-vpn/amneziawg-go/v3@${AMNEZIAWG_VERSION}" \
        "github.com/sagernet/gomobile@${GOMOBILE_VERSION}"
    go mod tidy
}

install_gomobile() {
    go install \
        "github.com/sagernet/gomobile/cmd/gomobile@${GOMOBILE_VERSION}" \
        "github.com/sagernet/gomobile/cmd/gobind@${GOMOBILE_VERSION}"
}

android() {
    : "${ANDROID_HOME:?ANDROID_HOME is not set}"
    : "${ANDROID_NDK_HOME:?ANDROID_NDK_HOME is not set}"
    install_gomobile
    export PATH="$(go env GOPATH)/bin:$PATH"
    mkdir -p "$(dirname "$OUTPUT")"
    local IFS=","
    gomobile bind -v \
        -o "$OUTPUT" \
        -target "$ANDROID_TARGETS" \
        -androidapi "$ANDROID_API" \
        -javapkg app.iykyk.core \
        -libname iykyk \
        -trimpath -buildvcs=false \
        -tags "${TAGS[*]}" \
        -ldflags "$(IFS=" "; echo "${LDFLAGS[*]}")" \
        ./iykyk github.com/sagernet/sing-box/experimental/libbox
    ls -lh "$OUTPUT"
}

# Windows: no gomobile; the same core as a standalone service executable.
# NaiveProxy needs cronet (cgo), so it is left out of the desktop build for now.
WINDOWS_TAGS=(
    with_gvisor
    with_quic
    with_wireguard
    with_utls
    badlinkname
    tfogo_checklinkname0
)

windows() {
    mkdir -p build/windows
    local IFS=","
    GOOS=windows GOARCH=amd64 CGO_ENABLED=0 go build -v \
        -trimpath -buildvcs=false \
        -tags "${WINDOWS_TAGS[*]}" \
        -ldflags "$(IFS=" "; echo "${LDFLAGS[*]}")" \
        -o build/windows/iykyk-service.exe \
        ./cmd/iykyk-service
    ls -lh build/windows/iykyk-service.exe
}

case "${1:-}" in
    deps) deps ;;
    android) android ;;
    windows) windows ;;
    *) echo "usage: $0 deps|android|windows" >&2; exit 2 ;;
esac
