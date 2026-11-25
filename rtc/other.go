//go:build !android

package main

import "net"

func androidNetwork() {}

func localAddrs() ([]net.Addr, error) { return net.InterfaceAddrs() }
