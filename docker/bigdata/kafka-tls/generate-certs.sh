#!/usr/bin/env bash
set -euo pipefail

# One-time (or re-run-to-rotate) generation of the CA + per-broker
# certificates for Kafka's inter-broker and client TLS listener. Output
# goes to ./certs/ (gitignored - private key material, never committed).
#
# PKCS12, not PEM - confirmed live that apache/kafka:3.7.0's own docker
# entrypoint (/etc/kafka/docker/configure) unconditionally requires a
# keystore password file (KAFKA_SSL_KEY_CREDENTIALS/KAFKA_SSL_KEYSTORE_
# CREDENTIALS) whenever SSL is enabled, regardless of keystore type - but
# Kafka's own SSL engine then REJECTS a keystore password on a PEM-type
# keystore ("SSL key store password cannot be specified with PEM format,
# only key password may be specified"), a genuine incompatibility between
# this image's entrypoint and PEM. PKCS12 keystores legitimately have (and
# require) a password, sidestepping the conflict entirely - and openssl can
# build a PKCS12 truststore too (-nokeys, cert only), so this still doesn't
# need keytool/a JRE.
#
# Server-side TLS only (encryption + broker authentication) - client certs
# aren't issued and KAFKA_SSL_CLIENT_AUTH stays "none", so every client
# (Kafka Connect, Schema Registry, kafka-ui, Flink) only needs the shared
# truststore.p12 + its password, not its own keystore.
#
# Usage: docker/bigdata/kafka-tls/generate-certs.sh

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="$SCRIPT_DIR/certs"
DAYS=3650
PASSWORD=$(openssl rand -hex 16)

mkdir -p "$OUT"

echo "Generating CA ..."
openssl req -new -x509 -nodes -newkey rsa:2048 \
  -keyout "$OUT/ca.key" -out "$OUT/ca.crt" \
  -days "$DAYS" -subj "/CN=bigdata-kafka-ca"

for broker in kafka kafka-2 kafka-3; do
  echo "Generating cert for ${broker} ..."
  openssl req -new -nodes -newkey rsa:2048 \
    -keyout "$OUT/${broker}.key" -out "$OUT/${broker}.csr" \
    -subj "/CN=${broker}"
  openssl x509 -req -in "$OUT/${broker}.csr" \
    -CA "$OUT/ca.crt" -CAkey "$OUT/ca.key" -CAcreateserial \
    -out "$OUT/${broker}.crt" -days "$DAYS" \
    -extfile <(printf "subjectAltName=DNS:%s" "$broker")
  openssl pkcs12 -export \
    -in "$OUT/${broker}.crt" -inkey "$OUT/${broker}.key" \
    -name "$broker" -out "$OUT/${broker}.keystore.p12" -passout pass:"$PASSWORD"
  rm -f "$OUT/${broker}.csr"
done

# Shared truststore (CA cert only, no private key) - every broker and every
# client trusts the same CA, same shared password as the keystores above
# (single demo-environment password reused everywhere - not meaningfully
# more secret per-file, and simpler than tracking N different passwords).
#
# keytool, not `openssl pkcs12 -export -nokeys` - confirmed live that a
# cert-only PKCS12 built by OpenSSL 3.x loads into Java with zero trust
# anchors ("InvalidAlgorithmParameterException: the trustAnchors parameter
# must be non-empty"), even though the file itself isn't corrupt - Java's
# PKCS12 truststore parser wants the "trusted certificate" bag attributes
# keytool sets, which OpenSSL's export doesn't produce the same way. Runs
# keytool from a disposable container instead of requiring a JRE on the
# host - reuses the apache/kafka image already pulled for this stack rather
# than pulling something new just for this one step.
docker run --rm -v "$OUT":/certs --entrypoint keytool apache/kafka:3.7.0 \
  -importcert -alias ca -file /certs/ca.crt -keystore /certs/truststore.p12 \
  -storetype PKCS12 -storepass "$PASSWORD" -noprompt

echo "$PASSWORD" > "$OUT/creds.txt"

chmod 644 "$OUT"/*.p12 "$OUT"/*.crt "$OUT/creds.txt"
chmod 600 "$OUT"/*.key
echo "Done. Certs written to $OUT/ (shared keystore/truststore password in creds.txt)"
