#!/bin/sh
# wifi-check.sh — report whether this device is reaching the internet
# over Wi-Fi or mobile data, from inside the phone-node guest.
#
# How it works: a UDP "connect" sends no packets, but makes the kernel
# choose a route and a source address. A phone on Wi-Fi sources from a
# LAN address (192.168/16 or 172.16/12); on mobile data it sources from
# the carrier-assigned address (10/8 or 100.64/10 CGNAT).
# Needs only perl (core IO::Socket), which the guest already has.
src=$(perl -MIO::Socket::INET -e '
  $s = IO::Socket::INET->new(PeerAddr => "8.8.8.8", PeerPort => 53, Proto => "udp")
    or do { exit };
  print $s->sockhost;
')
case "$src" in
  192.168.*|172.1[6-9].*|172.2[0-9].*|172.3[01].*)
    echo "WIFI src=$src" ;;
  10.*)
    echo "MOBILE src=$src (10/8 carrier space)" ;;
  100.6[4-9].*|100.[7-9][0-9].*|100.1[01][0-9].*|100.12[0-7].*)
    echo "MOBILE src=$src (100.64/10 carrier CGNAT space)" ;;
  "")
    echo "NONE (no route to the internet)" ;;
  *)
    echo "UNKNOWN src=$src" ;;
esac
