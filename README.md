# spring-boot-besu-tip-jar

Spring Boot 4 + Kotlin service that compiles and deploys the [`TipJar`](hardhat/contracts/TipJar.sol)
smart contract to a local [Hyperledger Besu](https://besu.hyperledger.org/) dev network using
[web3j](https://docs.web3j.io/).

## Stack

| Component | Version |
|---|---|
| Spring Boot | 4.1.0 |
| Kotlin | 2.4.0 (Java 21) |
| web3j | 5.0.3 |
| Hardhat | 2.x (contract compile + tests) |
| Maven | wrapper included (`./mvnw`) |
| Besu | 26.6.1, single-validator QBFT via Docker Compose |

## Prerequisites  

- JDK 21
- Node.js + npm (Hardhat compiles and tests the contracts during the build)
- Docker (used for the Besu node and the Testcontainers integration test — not for the build)

## Build pipeline

Contracts are owned by the [Hardhat](https://hardhat.org) workspace in [`hardhat/`](hardhat/);
the Maven build drives it (see `pom.xml`). The `generate-sources` phase:

1. **`npm ci`** — installs Hardhat, pinned by `package-lock.json` (solc 0.8.30 is
   fetched by Hardhat per [`hardhat.config.js`](hardhat/hardhat.config.js)).
2. **`npx hardhat compile`** — compiles [`contracts/TipJar.sol`](hardhat/contracts/TipJar.sol)
   with **`evmVersion: prague`** pinned in the config (solc's default EVM target moves
   between releases). The EVM target must not be newer than the latest fork activated in
   [`besu/genesis.json`](besu/genesis.json) (`pragueTime: 0`), otherwise deployment
   fails with `Invalid opcode` (e.g. `PUSH0` on a pre-Shanghai chain).
3. **Export + codegen** — [`scripts/export-web3j-artifacts.js`](hardhat/scripts/export-web3j-artifacts.js)
   flattens the Hardhat artifacts to `.abi`/`.bin`, and web3j codegen turns them into a
   type-safe `io.shudong.tipjar.contracts.TipJar` class under `target/generated-sources/web3j`.

The Hardhat contract tests run in the Maven `test` phase alongside the JVM suites.

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

### Verifying the node

```bash
docker compose ps                                  # expect "Up (healthy)"
curl "http://localhost:8545/readiness?minPeers=0"  # expect {"status":"UP"} — single-node needs minPeers=0

# chain id — expect 0x539 (= 1337)
curl -s -X POST http://localhost:8545 -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","method":"eth_chainId","params":[],"id":1}'

# latest block — run twice; the number must advance (QBFT can serve RPC yet not mine)
curl -s -X POST http://localhost:8545 -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}'
```

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

Deployment is owned by the Hardhat workspace (the app only *talks to* a deployed contract):

```bash
# sanity-check connectivity
curl http://localhost:8080/api/contract/node-info

# deploy TipJar
cd hardhat && npx hardhat run scripts/deploy.js --network besu
```

Example output:

```
TipJar deployed at 0x42699A7612A82f1d9C36148af9C77354759b210b (owner: 0xFE3B557E8Fb62b89F4916B721be55cEb828dBd73)
If this differs from web3.contract-address, update application.yml and restart the app.
```

All `/api/tipjar` endpoints talk to the **single contract configured** in
`application.yml` (`web3.contract-address`). The default value is the address the
*first* contract deployed by dev account #1 always gets on a **fresh chain**
(contract addresses derive from sender + nonce), so on a clean `docker compose up`
the deploy above matches the config out of the box. If the account has sent other
transactions, paste the printed address into `application.yml` and restart the app.

## 4. Interact with the contract

All interactions sign with the configured account (dev account #1):

```bash
# send a tip (value in wei)
curl -X POST http://localhost:8080/api/tipjar/tip \
  -H 'Content-Type: application/json' \
  -d '{"message": "great work!", "amountWei": 1000000000000000000}'

# contract state: owner, running tip total, current balance
curl http://localhost:8080/api/tipjar

# full tip history, read from Tipped event logs
curl http://localhost:8080/api/tipjar/tips

# withdraw the balance to the owner (reverts if signer is not the owner)
curl -X POST http://localhost:8080/api/tipjar/withdraw
```

| Endpoint | Method | Description |
|---|---|---|
| `/api/contract/node-info` | GET | Chain id, client version, latest block |
| `/api/tipjar` | GET | Owner, `totalTips`, contract balance |
| `/api/tipjar/tip` | POST | Send a tip with a message |
| `/api/tipjar/tips` | GET | All tips (decoded `Tipped` events) |
| `/api/tipjar/withdraw` | POST | Withdraw balance to the owner |

Invalid input returns `400`; on-chain reverts (e.g. withdrawing as non-owner)
return `422` with the revert reason:

```bash
# zero tip amount — rejected with 400 before reaching the chain
curl -X POST http://localhost:8080/api/tipjar/tip \
  -H 'Content-Type: application/json' \
  -d '{"message": "should fail", "amountWei": 0}'
```

## How it works

- [`Web3jConfig`](src/main/kotlin/io/shudong/tipjar/config/Web3jConfig.kt) exposes `Web3j`,
  `Credentials`, a chain-id-aware `RawTransactionManager`, and a zero-gas-price
  `StaticGasProvider` as beans.
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
| `web3.contract-address` | first-deploy address on a fresh chain | The TipJar all `/api/tipjar` endpoints use |
| `web3.gas-price` | `0` | Dev network runs with `--min-gas-price=0` |
| `web3.gas-limit` | `4000000` | Per-transaction gas limit |

## Testing

```bash
./mvnw test
```

Three complementary suites:

- **Web slice tests** — [`TipJarControllerTest`](src/test/kotlin/io/shudong/tipjar/TipJarControllerTest.kt)
  covers JSON shapes and error mapping (400/422) with `TipJarService` mocked via
  [springmockk](https://github.com/Ninja-Squad/springmockk) (`@MockkBean`) — the project's
  mocking flavor of choice, Kotlin-friendly unlike Mockito.
- **Contract tests** — [`hardhat/test/TipJar.test.js`](hardhat/test/TipJar.test.js) covers the contract
  logic (ownership, tipping, event emission, custom-error reverts, withdrawal) on the
  in-process Hardhat Network (`hardfork: prague`) — runs in ~1s with no node or Docker,
  against the same prague bytecode the build deploys. Run standalone with `npx hardhat test` from `hardhat/`.
- **Integration tests** — [`TipJarApiIntegrationTest`](src/test/kotlin/io/shudong/tipjar/TipJarApiIntegrationTest.kt)
  boots the real `hyperledger/besu` image via Testcontainers with the same [`besu/`](besu/)
  config as `docker-compose.yml`, starts the full Spring context against it, and drives the
  REST API end-to-end: deploy → tip → history → validation error → withdraw, including the
  `Tipped` console listener (asserted via captured log output). Takes ~1 min; skipped
  automatically when Docker is unavailable. Note: Besu's `/readiness` endpoint needs
  `?minPeers=0` on a single-node network.

## Notes

- The EVM target is pinned explicitly in `hardhat.config.js` (`evmVersion: "prague"`)
  because solc's default moves between releases; `web3j-maven-plugin` was rejected
  for the same reason (no way to pass `--evm-version`).
- Hardhat is deliberately on the 2.x line: the diamond-pattern plugin ecosystem and
  most references still target it over Hardhat 3.

## License

[MIT](LICENSE)
