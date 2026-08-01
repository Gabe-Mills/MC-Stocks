# MC-Stocks

MC-Stocks is a Paper 1.21.x plugin that adds a server-simulated stocks and cryptocurrency market.
It uses Vault for economy transactions, so it works with EssentialsX Economy through Vault.

## Build

```powershell
.\gradlew.bat build
```

The server-ready jar is produced in `build/libs/MC-Stocks-1.0.0.jar`.

## Runtime Requirements

- Paper 1.21.x
- Java 21
- Vault
- A Vault economy provider such as EssentialsX Economy

## Player Commands

- `/stocks` opens the stock menu.
- `/stocks buy <symbol> <amount>`
- `/stocks sell <symbol> <amount>`
- `/stocks limit <buy|sell> <symbol> <amount> <targetPrice>`
- `/stocks orders`
- `/stocks cancel <orderId>`
- `/stocks orderbook <symbol>`
- `/stocks history <symbol>`
- `/stocks portfolio`
- `/stocks price <symbol>`
- `/stocks movers`
- `/stocks top [portfolio|gain|profit]`

## Admin Commands

- `/stocksadmin reload`
- `/stocksadmin pause`
- `/stocksadmin resume`
- `/stocksadmin setprice <symbol> <price>`
- `/stocksadmin event <symbol|all> <bull|bear|crash|pump>`
- `/stocksadmin inspect <player>`

## GitHub Releases

The included GitHub Actions workflow builds the plugin on every push and pull request.
Push a version tag such as `v1.0.0` to publish a GitHub Release with the jar attached.
