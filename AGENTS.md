# AGENTS.md

Guidance for AI coding agents (Claude Code, GitHub Copilot, and others) working in this repository.

## What this is

Spring Boot 4 + Kotlin (JDK 21) service that compiles, deploys, and interacts with the
`TipJar` Solidity contract on a local single-validator Besu QBFT network, via web3j 5.
Builds need **Node.js + npm** (solc-js compiles the contract); Docker is only needed to
run the node and the Testcontainers e2e test.

## Commands

```bash
docker compose up -d          # start the Besu node (required by spring-boot:run, NOT by tests)
./mvnw clean package          # build: npm ci + solc-js → web3j codegen → kotlin compile → tests
./mvnw spring-boot:run        # run the app (Besu must already be up; it reads chain id at startup)
./mvnw test                   # both test suites
./mvnw test -Dtest=TipJarContractTest        # fast contract tests only (~1s, no Docker)
./mvnw test -Dtest=TipJarApiIntegrationTest  # Testcontainers e2e (~1 min, needs Docker)
```

`docker compose down` wipes the chain (state lives in the container); `stop`/`start` preserves it.

## The one invariant that breaks everything

**The solc EVM target must not exceed the newest fork activated in the chain genesis.**
Three files encode this and must stay in sync:

- `pom.xml` → `<solc.evm-version>prague</solc.evm-version>` (passed to `build-tools/solc/compile.js`)
- `besu/genesis.json` → `pragueTime: 0` (real node; genesis changes need `docker compose down && up`)
- `src/test/resources/embedded-genesis.json` → `pragueTime: 1` (embedded test EVM)

If the compiler targets a newer fork than a chain activates, deployment fails with
`Invalid opcode` (all gas consumed, empty revert reason — e.g. `PUSH0` on a pre-Shanghai
chain). The contract tests catch this at build time.

## Build pipeline (pom.xml)

`generate-sources` runs three `exec-maven-plugin` executions: `npm ci` in `build-tools/solc/`
(installs solc-js, pinned to 0.8.30 by the lockfile), `node build-tools/solc/compile.js`
compiles `contracts/TipJar.sol` to `target/solidity/`, then web3j codegen produces the
typed wrapper `io.shudong.tipjar.contracts.TipJar` under `target/generated-sources/web3j`.
Everything downstream (services, tests) is written against this wrapper.

Deliberate choices, do not "simplify" them away:

- `web3j-maven-plugin` is NOT used — it cannot pass `--evm-version` to solc.
- The `solcjs` CLI is NOT used either (no `--evm-version` flag); `compile.js` calls the
  standard-JSON API, which accepts `evmVersion`. Do not rely on solc's default EVM
  target — it moves between compiler releases.
- `maven-compiler-plugin`'s default executions are disabled and re-registered so the Kotlin
  compiler runs first and compiles the generated Java wrapper alongside Kotlin sources.
- The extra `<repositories>` (Hyperledger JFrog, Consensys, Splunk) exist solely for
  web3j-evm's transitive Besu dependencies, which are not on Maven Central.

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
- `TipJarService` loads the wrapper at a caller-supplied address per request; tip history
  is reconstructed via `eth_getLogs` filtered on the `Tipped` event topic (no local state).
- `TippedEventLogger` subscribes at startup to a **topic-only** filter (no address), so it
  logs tips to every TipJar instance, including ones deployed later. It watches from
  LATEST — historical tips are `/tips`' job.
- `TipJarController` error mapping: `IllegalArgumentException`/`ContractCallException` → 400,
  web3j `TransactionException` (on-chain revert) → 422.

## Testing quirks (hard-won, easy to rediscover painfully)

web3j-evm embedded EVM (`TipJarContractTest`):
- Its default genesis is **pre-Shanghai**; the custom `embedded-genesis.json` is mandatory.
- Fork timestamps of `0` are treated as *unset* — use `1`, with genesis `timestamp: 0x1`.
- `eth_chainId` is unimplemented — tests sign with plain `Credentials`, not the app's
  chain-id-aware transaction manager.
- It embeds Besu 25.2.1 internals regardless of the real node's version; forks newer than
  that cannot be tested embedded.
- On failed transactions it dumps an opcode trace to the console — useful, not a bug.

Testcontainers (`TipJarApiIntegrationTest`):
- Besu's `/readiness` returns 503 forever on a single-node network (default wants ≥ 1 peer);
  the wait strategy must use `/readiness?minPeers=0`.
- Ordered end-to-end scenario (`@Order`), one container + one Spring context for the class;
  auto-skips when Docker is unavailable.

Spring Boot 4 specifics: `TestRestTemplate` no longer exists (use `RestClient`);
the Boot parent does not manage Testcontainers versions (pinned in pom).
