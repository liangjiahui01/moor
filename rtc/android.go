//go:build android

package main

import (
	"os"
	"strconv"

	"github.com/wlynxg/anet"
)

// androidNetwork: since Android 11 apps may not list network interfaces over netlink the usual
// way; anet has a way that works, but needs the Android version, which the app passes in.
func androidNetwork() {
	if v, err := strconv.Atoi(os.Getenv("MOOR_ANDROID_SDK")); err == nil && v > 0 {
		anet.SetAndroidVersion(uint(sdkToRelease(v)))
	}
}

// sdkToRelease maps an API level to the Android release number anet expects.
func sdkToRelease(sdk int) int {
	switch {
	case sdk >= 35:
		return 15
	case sdk >= 34:
		return 14
	case sdk >= 33:
		return 13
	case sdk >= 31:
		return 12
	case sdk >= 30:
		return 11
	default:
		return 10
	}
}
