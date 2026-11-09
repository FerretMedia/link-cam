#!/bin/sh
# The build number for a version: 1.2.3 becomes 10203. Android's versionCode
# and the iPad's CFBundleVersion both use it, so they always go up together.
# Minor and patch stay below 100.
set -eu
echo "$1" | awk -F. '
    NF != 3 || $1 !~ /^[0-9]+$/ || $2 !~ /^[0-9]+$/ || $3 !~ /^[0-9]+$/ || $2 > 99 || $3 > 99 {
        print "version must be major.minor.patch with minor and patch below 100: " $0 > "/dev/stderr"; exit 1
    }
    { print $1 * 10000 + $2 * 100 + $3 }'
