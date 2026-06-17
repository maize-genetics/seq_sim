# Development

Guidance for building, testing, and contributing to seq_sim.

## Tech Stack

- **Language:** Kotlin 2.2.20 (JVM target: Java 21)
- **CLI Framework:** Clikt 5.0.3
- **Logging:** Log4j2 2.24.3
- **YAML Parser:** SnakeYAML 2.3
- **Build Tool:** Gradle with Kotlin DSL
- **Environment Manager:** pixi (conda-based)

## Build Commands

```bash
./gradlew build          # Build project
./gradlew installDist    # Build project and generate seq_sim launcher at build/install/seq_sim/bin/seq_sim
./gradlew test           # Run tests
./gradlew clean build    # Clean build
./gradlew jar            # Generate JAR
```

To run the application directly via Gradle (instead of the installed launcher):

```bash
./gradlew run --args="orchestrate --config pipeline_config.yaml"
```

### Running a Single Test

```bash
./gradlew test --tests "ClassName"                 # single test class
./gradlew test --tests "ClassName.testMethodName"  # single test method
```

## Running Integration Tests Locally (Docker)

AnchorWave is only available on Linux via bioconda, which makes local
integration testing painful on macOS. The repository ships a
reproducible Linux dev container that has AnchorWave, PHGv2, and the
seq-sim pixi env pre-baked so the pipeline "just runs" on any host that
has Docker installed.

```bash
# Build the image once (~10-15 min; only re-runs when Dockerfile or pixi/phg envs change)
./scripts/dev.sh build

# Run tests at three escalating levels (all inside the container):
./scripts/dev.sh test          # pure unit tests (fast, no external binaries)
./scripts/dev.sh integration   # per-step tests against real AnchorWave/PHG binaries
./scripts/dev.sh e2e           # full `orchestrate` smoke on tiny synthetic fixtures
./scripts/dev.sh all           # all of the above

# Interactive shell for ad-hoc debugging:
./scripts/dev.sh shell

# Or run any pipeline command inside the container:
./scripts/dev.sh run -- orchestrate --config my_pipeline.yaml
```

The same `scripts/dev.sh` entry points are used by CI, so "works on my
laptop" and "works on main" diverge much less often than they used to.

See `docker/Dockerfile.dev` for the full image spec.
