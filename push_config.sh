#!/bin/bash
#
# Script to push a printer configuration file to an Android device for a specific user.
#

set -euo pipefail

if [ "$#" -ne 2 ]; then
    echo "Usage: $0 <config_file> <user_id>"
    exit 1
fi

filename="$1"
user="$2"

adb push $filename /data/user/$user/com.google.virtualprinter/files/printer_config.json
uid=$(adb shell pm list packages -U --user $user com.google.virtualprinter | tr -d '\r' | sed 's/.*uid://')
adb shell chown -R $uid:$uid /data/user/$user/com.google.virtualprinter/files
adb shell restorecon -Rv /data/user/$user/com.google.virtualprinter/files/printer_config.json
