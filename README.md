# spring-boot-besu-tip-jar

Spring Boot 4 + Kotlin service that compiles and deploys the [`TipJar`](contracts/TipJar.sol)
smart contract to a local [Hyperledger Besu](https://besu.hyperledger.org/) dev network using
[web3j](https://docs.web3j.io/).

## Stack

| Component | Version |
|---|---|
| Spring Boot | 4.1.0 |
| Kotlin | 2.4.0 (Java 21) |
| web3j | 5.0.3 |
| Maven | wrapper included (`./mvnw`) |
| Besu | 26.6.1, single-validator QBFT via Docker Compose |

## Prerequisites  

- JDK 21
- Node.js + npm (used for the Solidity compiler during the build)
- Docker (used for the Besu node and the Testcontainers integration test — not for the build)

## Build pipeline

The `generate-sources` phase does three things (see `pom.xml`):

1. **Fetch the compiler** — `npm ci` in [`build-tools/solc/`](build-tools/solc/) installs
   [solc-js](https://github.com/argotorg/solc-js) pinned to `0.8.30` via the lockfile.
2. **Compile the contract** — [`build-tools/solc/compile.js`](build-tools/solc/compile.js)
   compiles [`contracts/TipJar.sol`](contracts/TipJar.sol) with **`evmVersion: prague`**
   (passed explicitly via standard-JSON; solc's default EVM target moves between releases).
   The EVM target must not be newer than the latest fork activated in
   [`besu/genesis.json`](besu/genesis.json) (`pragueTime: 0`), otherwise deployment
   fails with `Invalid opcode` (e.g. `PUSH0` on a pre-Shanghai chain).
3. **Generate the wrapper** — web3j codegen turns the ABI/bytecode into a type-safe
   `io.shudong.tipjar.contracts.TipJar` class under `target/generated-sources/web3j`.

## 1. Start the local Besu dev network

```bash
docker compose up -d
```

This starts a **single-validator QBFT network** on `localhost:8545` (chain id 1337) defined by
[`docker-compose.yml`](docker-compose.yml) and the files in [`besu/`](besu/):

- `genesis.json` — QBFT genesis: Prague EVM active from genesis, 2s block period, empty
  blocks throttled to one per 60s, zero base fee, and the three well-known Besu dev
  accounts pre-funded.
- `nodekey` — the node/validator private key (dev account #1).
- `validators.json` — input used to generate the QBFT `extraData`
  (`besu rlp encode --type=QBFT_EXTRA_DATA`).

> **Why not `--network=dev`?** Besu 26.x removed PoW *and* Clique block production, but the
> built-in dev network genesis is still ethash — on Besu ≥ 26 it accepts transactions and never
> mines them. QBFT is the supported way to produce blocks locally.

The chain state lives inside the container: `docker compose down` resets the chain,
`docker compose stop`/`start` preserves it.

The app signs as dev account #1 (`0xfe3b557e8fb62b89f4916b721be55ceb828dbd73`); its private key
is configured in [`application.yml`](src/main/resources/application.yml) — it is a publicly
documented dev key, never use it outside local development.

## 2. Run the app

```bash
./mvnw spring-boot:run
```

On startup the app connects to the RPC URL (`http://localhost:8545` by default) and reads the
node's chain id, so the Besu node must be running first.

## 3. Deploy the contract

```bash
# sanity-check connectivity
curl http://localhost:8080/api/contract/node-info

# deploy TipJar
curl -X POST http://localhost:8080/api/contract/deploy
```

Example response:

```json
{
  "contractAddress": "0xa50a51c09a5c451c52bb714527e1974b686d8e77",
  "transactionHash": "0x22c16d76755ede9e120635be93859382a17f3bdc4ad8c9dfa626eb99c2fb81dc",
  "blockNumber": 6133,
  "owner": "0xfe3b557e8fb62b89f4916b721be55ceb828dbd73"
}
```

## 4. Interact with the contract

All interaction endpoints take the deployed contract address in the path and sign
transactions with the configured account (dev account #1):

```bash
ADDR=0x...   # contractAddress from the deploy response

# send a tip (value in wei)
curl -X POST http://localhost:8080/api/tipjar/$ADDR/tip \
  -H 'Content-Type: application/json' \
  -d '{"message": "great work!", "amountWei": 1000000000000000000}'

# contract state: owner, running tip total, current balance
curl http://localhost:8080/api/tipjar/$ADDR

# full tip history, read from Tipped event logs
curl http://localhost:8080/api/tipjar/$ADDR/tips

# withdraw the balance to the owner (reverts if signer is not the owner)
curl -X POST http://localhost:8080/api/tipjar/$ADDR/withdraw
```

| Endpoint | Method | Description |
|---|---|---|
| `/api/contract/node-info` | GET | Chain id, client version, latest block |
| `/api/contract/deploy` | POST | Deploy a new TipJar |
| `/api/tipjar/{address}` | GET | Owner, `totalTips`, contract balance |
| `/api/tipjar/{address}/tip` | POST | Send a tip with a message |
| `/api/tipjar/{address}/tips` | GET | All tips (decoded `Tipped` events) |
| `/api/tipjar/{address}/withdraw` | POST | Withdraw balance to the owner |

Invalid input (e.g. `amountWei: 0`) returns `400`; on-chain reverts return `422`.

## How it works

- [`Web3jConfig`](src/main/kotlin/io/shudong/tipjar/config/Web3jConfig.kt) exposes `Web3j`,
  `Credentials`, a chain-id-aware `RawTransactionManager`, and a zero-gas-price
  `StaticGasProvider` as beans.
- [`TipJarDeploymentService`](src/main/kotlin/io/shudong/tipjar/service/TipJarDeploymentService.kt)
  calls the generated wrapper's `deploy(...)` and returns the address, tx hash, and on-chain owner.
- [`TipJarService`](src/main/kotlin/io/shudong/tipjar/service/TipJarService.kt) loads the wrapper
  at a given address for tips/withdrawals, and reconstructs the tip history via `eth_getLogs`
  filtered on the `Tipped` event topic.
- [`TippedEventLogger`](src/main/kotlin/io/shudong/tipjar/service/TippedEventLogger.kt) subscribes
  to `Tipped` logs at startup (topic-only filter, so every TipJar instance is covered) and logs
  each tip to the console. Over HTTP this uses web3j's filter polling, tuned to the 2s block
  period in [`Web3jConfig`](src/main/kotlin/io/shudong/tipjar/config/Web3jConfig.kt).

## Configuration

All settings live under the `web3` prefix in `application.yml`:

| Property | Default | Description |
|---|---|---|
| `web3.rpc-url` | `http://localhost:8545` | Besu JSON-RPC endpoint |
| `web3.private-key` | Besu dev account #1 | Signing key |
| `web3.gas-price` | `0` | Dev network runs with `--min-gas-price=0` |
| `web3.gas-limit` | `4000000` | Per-transaction gas limit |

## Testing

```bash
./mvnw test
```

Two complementary suites:

- **Contract tests** — [`TipJarContractTest`](src/test/kotlin/io/shudong/tipjar/TipJarContractTest.kt)
  covers the contract logic (ownership, tipping, event emission, reverts, withdrawal) on
  [web3j-evm](https://github.com/hyperledger-labs/web3j-evm)'s in-process EVM — runs in ~1s
  with no node or Docker, against the same prague bytecode the build deploys.
- **Integration tests** — [`TipJarApiIntegrationTest`](src/test/kotlin/io/shudong/tipjar/TipJarApiIntegrationTest.kt)
  boots the real `hyperledger/besu` image via Testcontainers with the same [`besu/`](besu/)
  config as `docker-compose.yml`, starts the full Spring context against it, and drives the
  REST API end-to-end: deploy → tip → history → validation error → withdraw, including the
  `Tipped` console listener (asserted via captured log output). Takes ~1 min; skipped
  automatically when Docker is unavailable. Note: Besu's `/readiness` endpoint needs
  `?minPeers=0` on a single-node network.

Two embedded-EVM quirks worth knowing:

- web3j-evm's Besu internals are not on Maven Central, hence the extra `<repositories>`
  in `pom.xml`.
- The embedded chain picks the EVM fork from the *current head timestamp*, and treats a
  fork time of `0` as unset. [`embedded-genesis.json`](src/test/resources/embedded-genesis.json)
  therefore sets `pragueTime: 1` **and** genesis `timestamp: 0x1` — with the web3j-evm
  default (pre-Shanghai DEV genesis), deploying the prague bytecode fails with an
  all-gas-consumed `PUSH0` invalid-opcode error.

## Notes

- The solc-js CLI (`solcjs`) is deliberately **not** used: it has no `--evm-version`
  flag. [`compile.js`](build-tools/solc/compile.js) calls the compiler's standard-JSON
  API instead, which accepts `evmVersion` — that keeps the EVM target in sync with the
  forks activated in the chain's genesis. `web3j-maven-plugin` was rejected for the
  same reason.

## License

[MIT](LICENSE)
