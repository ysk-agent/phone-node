#!/bin/sh
# First-launch setup inside the Debian rootfs (runs under proot as root).
# The app copies this script and the other /opt/phonenode files into the
# rootfs before invoking it. Idempotent; the .setup-done marker at the
# end is what the app checks.
set -e
export DEBIAN_FRONTEND=noninteractive

apt-get update
apt-get install -y --no-install-recommends openssh-server git ca-certificates

mkdir -p /root/.ssh /run/sshd /root/work /usr/local/bin
cp /opt/phonenode/authorized_keys /root/.ssh/authorized_keys
chmod 700 /root/.ssh
chmod 600 /root/.ssh/authorized_keys

tar -xzf /opt/phonenode/opencode.tgz -C /usr/local/bin
chmod 755 /usr/local/bin/opencode

sed -i 's/^#\?Port .*/Port 8022/' /etc/ssh/sshd_config
sed -i 's/^#\?PasswordAuthentication .*/PasswordAuthentication no/' /etc/ssh/sshd_config
sed -i 's/^#\?PermitRootLogin .*/PermitRootLogin prohibit-password/' /etc/ssh/sshd_config
ssh-keygen -A

# opencode runs are done inside a git repository (Moriya's rule: outside
# one, opencode stalls after startup in his environment).
cd /root/work
if [ ! -d .git ]; then
  git init -q
  git config user.email "node@phonenode.local"
  git config user.name "phonenode"
fi

touch /opt/phonenode/.setup-done
echo "node-setup: done"
