# Doc snapshot generator for ansible-core 2.18.8 with the collections pinned by the target repo's images.
# Build (needs network):   docker build -t ansibility-docgen:2.18.8 tools/docgen/snapshot
# Run (no network, no mounts; the gzipped snapshot is written to stdout):
#   docker run --rm --network none ansibility-docgen:2.18.8 --gzip > core-2.18.8.json.gz
# build.sh does both and writes plugin/src/main/resources/ansible-data/core-2.18.8.json.gz.
FROM python:3.13-alpine

ARG ANSIBLE_CORE_VERSION=2.18.8

RUN python3 -m pip install --no-cache-dir "ansible-core==${ANSIBLE_CORE_VERSION}"

COPY requirements.yml /tmp/requirements.yml
RUN ansible-galaxy collection install --force -r /tmp/requirements.yml -p /usr/share/ansible/collections \
 && rm -f /tmp/requirements.yml

COPY generate.py /opt/docgen/generate.py

WORKDIR /tmp
ENTRYPOINT ["python3", "/opt/docgen/generate.py"]
CMD []
