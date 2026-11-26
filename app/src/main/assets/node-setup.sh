#!/bin/sh
# First-launch setup inside the Debian rootfs (runs under proot as root).
# The app copies this script and the other /opt/phonenode files into the
# rootfs before invoking it. Idempotent; the .setup-done marker at the
# end is what the app checks.
set -e
export DEBIAN_FRONTEND=noninteractive

# Recover a previously interrupted dpkg run (e.g. an earlier setup
# attempt that died halfway): apt refuses to do anything until the
# pending configuration is finished. A no-op on a fresh rootfs.
dpkg --configure -a || echo "node-setup: dpkg recovery returned $?"

apt-get update
# Repair a broken state left by an interrupted earlier run (packages
# unpacked and "newest" while a dependency such as perl was never
# unpacked at all). The fix must run with NO package arguments: with
# arguments, apt only fixes breakage caused by that very request and
# still refuses pre-existing breakage. A no-op on a healthy system.
apt-get install -y -f
apt-get install -y --no-install-recommends openssh-server git ca-certificates

mkdir -p /root/.ssh /run/sshd /root/work /root/work-v2 /usr/local/bin \
  /root/oc-v1-home /root/oc-v2-home /usr/local/share/phonenode
cp /opt/phonenode/authorized_keys /root/.ssh/authorized_keys
chmod 700 /root/.ssh
chmod 600 /root/.ssh/authorized_keys

# OpenCode v1 and v2 coexist under separate binary names: upstream's
# v1 and v2 installers both provide an `opencode` command and cannot
# coexist under one name. Each tarball holds a single `opencode`
# binary; install it under its versioned name and record the
# tarball's sha256 in the marker run-node.sh's self-install checks,
# so the two install paths agree.
install_opencode() {
  # $1 = version label (v1|v2), $2 = tarball name, $3 = binary name
  if [ -f "/opt/phonenode/$2" ]; then
    tmp=$(mktemp -d)
    tar -xzf "/opt/phonenode/$2" -C "$tmp"
    mv "$tmp/opencode" "/usr/local/bin/$3"
    chmod 755 "/usr/local/bin/$3"
    rm -rf "$tmp"
    sha256sum "/opt/phonenode/$2" | cut -d' ' -f1 \
      > "/usr/local/share/phonenode/installed-$1"
  fi
}
install_opencode v1 opencode.tgz opencode-v1
install_opencode v2 opencode-v2.tgz opencode-v2
# Backwards compatibility for anything still invoking the old name.
ln -sf opencode-v1 /usr/local/bin/opencode

sed -i 's/^#\?Port .*/Port 8022/' /etc/ssh/sshd_config
sed -i 's/^#\?PasswordAuthentication .*/PasswordAuthentication no/' /etc/ssh/sshd_config
sed -i 's/^#\?PermitRootLogin .*/PermitRootLogin prohibit-password/' /etc/ssh/sshd_config
ssh-keygen -A

# opencode runs are done inside a git repository (Moriya's rule: outside
# one, opencode stalls after startup in his environment). Each version
# gets its own work dir: /root/work for v1, /root/work-v2 for v2.
for workdir in /root/work /root/work-v2; do
  cd "$workdir"
  if [ ! -d .git ]; then
    git init -q
    git config user.email "node@phonenode.local"
    git config user.name "phonenode"
  fi
done

touch /opt/phonenode/.setup-done
echo "node-setup: done"
