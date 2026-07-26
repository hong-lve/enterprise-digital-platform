#!/bin/bash
# Sets up NFS-backed shared storage for the kind cluster, replacing the
# default local-path StorageClass for anything that needs to survive a node
# failure (see flinkdeployment-ha.yaml's header comment for why local-path
# can't do this - a PV pinned to one node dies with that node).
#
# The NFS server runs directly on the kind host (not inside a kind node, and
# not requiring a fourth container) - kind node containers already reach the
# host via the "kind" docker network's gateway IP, so nothing extra needs to
# join that network. Run this ONCE per host, before applying any manifest
# that references the "nfs-client" StorageClass.
set -euo pipefail

KIND_NETWORK_SUBNET="$(docker network inspect kind --format '{{(index .IPAM.Config 0).Subnet}}')"
KIND_NETWORK_GATEWAY="$(docker network inspect kind --format '{{(index .IPAM.Config 0).Gateway}}')"
EXPORT_DIR=/srv/nfs/flink-ha

apt-get update -qq
apt-get install -y -qq nfs-kernel-server

mkdir -p "$EXPORT_DIR"
chown nobody:nogroup "$EXPORT_DIR"
chmod 777 "$EXPORT_DIR"
echo "$EXPORT_DIR $KIND_NETWORK_SUBNET(rw,sync,no_subtree_check,no_root_squash)" > /etc/exports
exportfs -ra
systemctl restart nfs-kernel-server
systemctl enable nfs-kernel-server

# UFW's default INPUT policy is DROP - without this, kind's node containers
# (on the other side of the docker bridge) can reach the NFS ports at the
# TCP/UDP level but every packet gets dropped before rpcbind/nfsd sees it,
# which surfaces as a plain "Connection timed out" on the mount side with no
# error logged on the server side at all (confirmed live - cost real time to
# track down since nothing on the NFS side looked wrong).
ufw allow from "$KIND_NETWORK_SUBNET" to any port 2049 proto tcp
ufw allow from "$KIND_NETWORK_SUBNET" to any port 111
ufw allow from "$KIND_NETWORK_SUBNET" to any port 111 proto udp

export KUBECONFIG=/root/.kube/config
helm repo add nfs-subdir-external-provisioner https://kubernetes-sigs.github.io/nfs-subdir-external-provisioner/ || true
helm repo update
helm install nfs-provisioner nfs-subdir-external-provisioner/nfs-subdir-external-provisioner \
  --set nfs.server="$KIND_NETWORK_GATEWAY" \
  --set nfs.path="$EXPORT_DIR" \
  --set storageClass.name=nfs-client \
  --set storageClass.defaultClass=false \
  -n flink
