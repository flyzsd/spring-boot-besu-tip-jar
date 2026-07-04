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
- Docker (used both for the Besu node and for the Solidity compiler during the build)

## Build pipeline

The `generate-sources` phase does two things (see `pom.xml`):

1. **Compile the contract** — runs `ethereum/solc:0.8.30` in Docker against
   [`contracts/TipJar.sol`](contracts/TipJar.sol) with **`--evm-version prague`**.
   The EVM target must not be newer than the latest fork activated in
   [`besu/genesis.json`](besu/genesis.json) (`pragueTime: 0`), otherwise deployment
   fails with `Invalid opcode` (e.g. `PUSH0` on a pre-Shanghai chain).
2. **Generate the wrapper** — web3j codegen turns the ABI/bytecode into a type-safe
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

## How it works

- [`Web3jConfig`](src/main/kotlin/io/shudong/tipjar/config/Web3jConfig.kt) exposes `Web3j`,
  `Credentials`, a chain-id-aware `RawTransactionManager`, and a zero-gas-price
  `StaticGasProvider` as beans.
- [`TipJarDeploymentService`](src/main/kotlin/io/shudong/tipjar/service/TipJarDeploymentService.kt)
  calls the generated wrapper's `deploy(...)` and returns the address, tx hash, and on-chain owner.

## Configuration

All settings live under the `web3` prefix in `application.yml`:

| Property | Default | Description |
|---|---|---|
| `web3.rpc-url` | `http://localhost:8545` | Besu JSON-RPC endpoint |
| `web3.private-key` | Besu dev account #1 | Signing key |
| `web3.gas-price` | `0` | Dev network runs with `--min-gas-price=0` |
| `web3.gas-limit` | `4000000` | Per-transaction gas limit |

## Notes

- The Maven build shows a platform warning for the solc image on Apple Silicon
  (`linux/amd64` vs `arm64`); it runs fine under emulation.
- `web3j-maven-plugin` was deliberately **not** used: it offers no way to pass
  `--evm-version` to solc, so the EVM target could not be kept in sync with the
  forks activated in the chain's genesis.
