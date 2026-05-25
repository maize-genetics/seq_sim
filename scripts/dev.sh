#!/usr/bin/env bash
#
# scripts/dev.sh - developer helper for running seq_sim tasks in the
# containerized Linux dev environment (docker/Dockerfile.dev).
#
# This is the single entry point for running integration tests locally on
# macOS without fighting pixi/conda around AnchorWave.
#
# Usage:
#   scripts/dev.sh build          # build/refresh the seq-sim-dev image
#   scripts/dev.sh shell          # drop into an interactive shell
#   scripts/dev.sh test           # ./gradlew test (unit tests only)
#   scripts/dev.sh integration    # ./gradlew integrationTest
#   scripts/dev.sh e2e            # ./gradlew e2eTest
#   scripts/dev.sh all            # test + integration + e2e
#   scripts/dev.sh run -- <args>  # ./gradlew run --args="<args>"
#   scripts/dev.sh exec <cmd...>  # run an arbitrary command in the container
#
# Environment overrides:
#   SEQ_SIM_UID / SEQ_SIM_GID    override the user id inside the container
#   SEQ_SIM_COMPOSE              path to a custom docker compose binary

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

COMPOSE_FILE="docker/docker-compose.yml"
COMPOSE="${SEQ_SIM_COMPOSE:-docker compose}"

SEQ_SIM_UID="${SEQ_SIM_UID:-$(id -u)}"
SEQ_SIM_GID="${SEQ_SIM_GID:-$(id -g)}"
export SEQ_SIM_UID SEQ_SIM_GID

compose() {
    # shellcheck disable=SC2086
    $COMPOSE -f "$COMPOSE_FILE" "$@"
}

# Run a command inside a fresh dev container, removing it on exit.
run_in_container() {
    compose run --rm dev "$@"
}

image_exists() {
    docker image inspect seq-sim-dev:latest >/dev/null 2>&1
}

ensure_image() {
    if ! image_exists; then
        echo "==> seq-sim-dev image not found; building now (first build takes ~10-15 min)..."
        compose build
    fi
}

# The pixi-cache named volume is seeded from the image's /var/cache/pixi
# the first time it is mounted, and from then on its perms persist for
# the lifetime of the volume. We always run the container as the host
# uid (`id -u`), so a volume seeded by an older image (which baked the
# cache as uid 1000) is unwritable from the host uid and breaks
# `pixi install` with "Permission denied" on the repodata lock. Detect
# that mismatch and refuse to proceed until the user nukes the volume.
ensure_pixi_cache_writable() {
    local volume_name
    volume_name="$($COMPOSE -f "$COMPOSE_FILE" config --format json 2>/dev/null \
        | jq -r '.volumes["pixi-cache"].name // empty' 2>/dev/null)"
    if [ -z "$volume_name" ]; then
        # Fall back to the default `<project>_<service-volume>` naming.
        volume_name="docker_pixi-cache"
    fi
    if ! docker volume inspect "$volume_name" >/dev/null 2>&1; then
        return 0
    fi
    # Probe the volume by trying to create a sentinel file as the host uid.
    if docker run --rm \
        --user "${SEQ_SIM_UID}:${SEQ_SIM_GID}" \
        -v "${volume_name}:/var/cache/pixi" \
        alpine:3.20 sh -c 'touch /var/cache/pixi/.seq-sim-write-probe && rm /var/cache/pixi/.seq-sim-write-probe' \
        >/dev/null 2>&1
    then
        return 0
    fi
    cat >&2 <<EOF
==> ERROR: the docker volume "${volume_name}" is not writable by uid ${SEQ_SIM_UID}.
    This usually means it was seeded by an older image that pinned the pixi
    cache to uid 1000. The current image marks /var/cache/pixi world-writable,
    but named volumes preserve their content (and perms) across rebuilds.

    Recreate the volume and rebuild the image:

        docker compose -f ${COMPOSE_FILE} down -v
        scripts/dev.sh build

    Then re-run your command.
EOF
    exit 1
}

usage() {
    sed -n '2,24p' "$0"
}

cmd="${1:-}"
shift || true

case "$cmd" in
    build)
        compose build "$@"
        ;;
    rebuild)
        compose build --no-cache "$@"
        ;;
    shell|sh)
        ensure_image
        run_in_container bash
        ;;
    test|unit)
        ensure_image
        # Non-login (`bash -c`, not `-lc`): Debian's /etc/profile would otherwise
        # reset PATH and drop /opt/micromamba/envs/phgv2-conda/bin (which holds
        # anchorwave, minimap2, ...). The Dockerfile sets PATH via ENV, which
        # is preserved here but blown away by a login shell.
        run_in_container bash -c "./gradlew test $*"
        ;;
    integration|int)
        ensure_image
        ensure_pixi_cache_writable
        run_in_container bash -c "./gradlew integrationTest $*"
        ;;
    e2e|smoke)
        ensure_image
        ensure_pixi_cache_writable
        run_in_container bash -c "./gradlew e2eTest $*"
        ;;
    all)
        ensure_image
        ensure_pixi_cache_writable
        run_in_container bash -c "./gradlew test integrationTest e2eTest $*"
        ;;
    run)
        ensure_image
        # Everything after `--` is passed to `gradlew run --args="..."`
        if [ "${1:-}" = "--" ]; then shift; fi
        args="$*"
        run_in_container bash -c "./gradlew run --args=\"$args\""
        ;;
    exec)
        ensure_image
        run_in_container "$@"
        ;;
    clean)
        compose down -v
        docker image rm seq-sim-dev:latest 2>/dev/null || true
        rm -rf .gradle-container .home-container
        ;;
    ""|-h|--help|help)
        usage
        ;;
    *)
        echo "Unknown command: $cmd" >&2
        echo ""
        usage
        exit 2
        ;;
esac
