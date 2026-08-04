# MC-Stocks

Paper 1.21.x plugin that adds a server-simulated stocks and cryptocurrency market.

**Vault is the only hard dependency.** Pair it with EssentialsX Economy (or any other Vault economy provider). LuckPerms and PlaceholderAPI are optional.

## Recommended plugins folder

```text
plugins/
├── EssentialsX.jar
├── EssentialsXEconomy.jar
├── Vault.jar
├── LuckPerms.jar
├── PlaceholderAPI.jar
└── MC-Stocks-1.1.0.jar
```

## Build

```bash
./gradlew build
```

The server-ready jar is produced in `build/libs/MC-Stocks-<version>.jar`.

## Runtime requirements

- Paper 1.21.x or Paper 26.2
- Java 21 for Paper 1.21.x; Java 25+ for Paper 26.2
- Vault + a Vault economy provider (EssentialsX Economy recommended)
- Optional: LuckPerms, PlaceholderAPI

## Player commands

- `/stocks` opens the stock menu (`stocks.market`)
- `/stocks buy <symbol> <amount>` (`stocks.buy`)
- `/stocks sell <symbol> <amount>` (`stocks.sell`)
- `/stocks limit <buy|sell> <symbol> <amount> <targetPrice>`
- `/stocks orders`
- `/stocks cancel <orderId>`
- `/stocks orderbook <symbol>`
- `/stocks history <symbol>`
- `/stocks portfolio`
- `/stocks price <symbol>`
- `/stocks movers`
- `/stocks top [portfolio|gain|profit]`

## Admin commands

- `/stocksadmin reload`
- `/stocksadmin pause` / `resume`
- `/stocksadmin setprice <symbol> <price>`
- `/stocksadmin resetprice <symbol|all>`
- `/stocksadmin freeze <symbol>` / `unfreeze <symbol>`
- `/stocksadmin event <symbol|all> <bull|bear|crash|pump>`
- `/stocksadmin inspect <player>`
- `/stocksadmin reverse <tradeId>`
- `/stocksadmin backup`

## PlaceholderAPI

Identifier: `mcstocks`

- `%mcstocks_market_status%`
- `%mcstocks_price_<SYMBOL>%`
- `%mcstocks_change_<SYMBOL>%`
- `%mcstocks_frozen_<SYMBOL>%`
- `%mcstocks_portfolio_value%`
- `%mcstocks_holding_<SYMBOL>%`
- `%mcstocks_holding_value_<SYMBOL>%`

## Storage and safety

- SQLite by default; optional MySQL/MariaDB via `database.type`
- Async single-thread database executor
- Per-player trade locks
- Audit log + automatic backups
- Wall-clock market scheduler (independent of server TPS)
- Configurable market hours, fees, taxes, circuit breakers, and price floors/ceilings
- Persists market state on each tick and on shutdown; recovers after a crash

## GitHub Releases

The included GitHub Actions workflow builds the plugin on every push and pull request.
Push a version tag such as `v1.1.0` to publish a GitHub Release with the jar attached.
