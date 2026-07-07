# AGENTS.md

Guidance for AI coding agents (Claude Code, GitHub Copilot, and others) working in this repository.

## Working conventions

**One approach, no dual paths.** When something can be done multiple ways, pick the single
approach that works in every target environment and remove the alternatives. Do not keep
parallel options around (Maven profiles, feature flags, opt-in fallbacks, "legacy" paths)
— they drift apart and double the maintenance surface.

**Mocking: springmockk only.** When a test needs mocks, use MockK via springmockk
(`@MockkBean`), never Mockito/`@MockitoBean` — it's the Kotlin-friendly flavor (no
final-class friction, `every { }` DSL). Mocks are for the web slice only; contract and
chain behavior must stay on the real EVM/node (that's what catches the genesis invariant).

## What this is

Spring Boot 4 + Kotlin (JDK 21) service that deploys and interacts with the `TipJar`
Solidity contract on a local single-validator Besu QBFT network, via web3j 5.
Contracts are owned by a **Hardhat 2** workspace in `hardhat/` (compile + contract
tests); the JVM app consumes the artifacts. Builds need **Node.js + npm**; Docker is
only needed to run the node and the Testcontainers e2e test.

## Commands

```bash
docker compose up -d          # start the Besu node (required by spring-boot:run, NOT by tests)
./mvnw clean package          # build: npm ci → hardhat compile → web3j codegen → kotlin → all tests
./mvnw spring-boot:run        # run the app (Besu must already be up; it reads chain id at startup)
./mvnw test                   # all test suites (incl. hardhat tests via exec)
cd hardhat && npx hardhat test  # contract tests only (~1s, no Docker)
cd hardhat && npx hardhat run scripts/deploy.js --network besu  # deploy TipJar (node must be up)
./mvnw test -Dtest=TipJarControllerTest      # web slice, springmockk (~1s, no Docker)
./mvnw test -Dtest=TipJarApiIntegrationTest  # Testcontainers e2e (~1 min, needs Docker)
```

`docker compose down` wipes the chain (state lives in the container); `stop`/`start` preserves it.

## The one invariant that breaks everything

**The solc EVM target must not exceed the newest fork activated in the chain genesis.**
Two files encode this and must stay in sync:

- `hardhat/hardhat.config.js` → `evmVersion: "prague"` (compile target) and
  `networks.hardhat.hardfork: "prague"` (test EVM fork — keeps the contract tests
  running the same fork they compile for)
- `besu/genesis.json` → `pragueTime: 0` (real node; genesis changes need `docker compose down && up`)

If the compiler targets a newer fork than a chain activates, deployment fails with
`Invalid opcode` (all gas consumed, empty revert reason — e.g. `PUSH0` on a pre-Shanghai
chain). The contract tests catch this at build time.

## Build pipeline (pom.xml)

`generate-sources` runs three `exec-maven-plugin` executions: `npm ci` (installs
Hardhat, pinned by `package-lock.json`), `npx hardhat compile` (compiles `contracts/`
with the evmVersion from `hardhat.config.js`), then `scripts/export-web3j-artifacts.js`
flattens the Hardhat artifacts into the `.abi`/`.bin` files web3j codegen consumes to
produce the typed wrapper `io.shudong.tipjar.contracts.TipJar` under
`target/generated-sources/web3j`. Everything downstream (services, tests) is written
against this wrapper. A fourth execution runs `npx hardhat test` in the `test` phase
(honors `-DskipTests`).

Deliberate choices, do not "simplify" them away:

- Contracts are compiled by Hardhat via npm, NOT via the `ethereum/solc` Docker image —
  the build must work in restricted environments without Docker Hub access. Do not
  reintroduce a Docker-based compile step, not even as an alternative.
- `web3j-maven-plugin` is NOT used — it cannot pass `--evm-version` to solc; the
  Hardhat config pins it explicitly. Do not rely on solc's default EVM target — it
  moves between compiler releases.
- Hardhat is pinned to the 2.x line deliberately: the diamond-pattern plugin ecosystem
  and most references target Hardhat 2; revisit Hardhat 3 when they've caught up.
- `maven-compiler-plugin`'s default executions are disabled and re-registered so the Kotlin
  compiler runs first and compiles the generated Java wrapper alongside Kotlin sources.

## Chain setup

Single-validator QBFT, chain id 1337, zero gas price, 2s blocks (empty blocks throttled
to 60s). QBFT because Besu 26.x removed PoW/Clique block production, so `--network=dev`
accepts transactions but never mines them. The app signs everything as Besu dev account #1
(`0xfe3b...bd73`, also the QBFT validator); its key in `application.yml` and `besu/nodekey`
are publicly documented dev keys — fine to commit, never reuse elsewhere.

## Runtime architecture

- `Web3jConfig` builds the `Web3j` bean with a **2s filter-polling interval** (matches the
  block period; web3j's default 15s makes event listening laggy) and a chain-id-aware
  `RawTransactionManager` (reads chain id from the node at startup — hence the node-up
  requirement).
- `TipJarService` talks to the single contract configured via `web3.contract-address`
  (the yml default is the deterministic first-deploy address on a fresh chain —
  sender + nonce 0); tip history is reconstructed via `eth_getLogs` filtered on the
  `Tipped` event topic (no local state). Deployment is Hardhat's job
  (`scripts/deploy.js`), not the app's; switching contracts requires a config
  change + restart.
- `TippedEventLogger` subscribes at startup to a **topic-only** filter (no address), so it
  logs tips to every TipJar instance, including ones deployed later. It watches from
  LATEST — historical tips are `/tips`' job.
- `TipJarController` error mapping: `IllegalArgumentException`/`ContractCallException` → 400,
  web3j `TransactionException` (on-chain revert) → 422.

## Testing quirks (hard-won, easy to rediscover painfully)

Hardhat contract tests (`hardhat/test/TipJar.test.js`):
- Run on the in-process Hardhat Network with `hardfork: "prague"` — keep that in sync
  with the compile `evmVersion`, or fork mismatches slip through to deployment.
- `changeEtherBalances` ignores gas fees, so exact balance deltas work even though
  Hardhat Network (unlike the Besu dev chain) charges gas.

Testcontainers (`TipJarApiIntegrationTest`):
- Besu's `/readiness` returns 503 forever on a single-node network (default wants ≥ 1 peer);
  the wait strategy must use `/readiness?minPeers=0`.
- Ordered end-to-end scenario (`@Order`), one container + one Spring context for the class;
  auto-skips when Docker is unavailable.

Spring Boot 4 specifics: `TestRestTemplate` no longer exists (use `RestClient`);
the Boot parent does not manage Testcontainers versions (pinned in pom);
test slices moved to per-module artifacts — `@WebMvcTest` needs the
`spring-boot-starter-webmvc-test` dependency and lives in
`org.springframework.boot.webmvc.test.autoconfigure`. springmockk must be 5.x
(4.x targets Boot 3 and does not work on Framework 7).
