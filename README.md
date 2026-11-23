# ArvyxCore 1.1.3 (ARVYX NETWORK)

**ArvyxCore** je modulárny, bezpečnostne orientovaný základný Minecraft Paper plugin pre sieť **ARVYX NETWORK**, navrhnutý pre **Paper 26.3**, **Java 25** a **Gradle Kotlin DSL**.

---

## 1. Architektúra siete a konfigurácia serverov

Sieť ARVYX NETWORK pozostáva z proxy **Velocity (`25565`)** a šiestich backend serverov:

- `lobby` (`25566`) — šablóna v `servers/lobby.yml`
- `skyrush-1` (`25571`) — šablóna v `servers/skyrush-1.yml`
- `skyrush-2` (`25572`) — šablóna v `servers/skyrush-2.yml`
- `skyrush-3` (`25573`) — šablóna v `servers/skyrush-3.yml`
- `chaos-1` (`25581`) — šablóna v `servers/chaos-1.yml`
- `chaos-2` (`25582`) — šablóna v `servers/chaos-2.yml`

Každá aréna v `arenas.yml` je viazaná poľom `server` na konkrétny `server.id` a zaregistruje sa **iba** na serveri, ktorému patrí.

### Bezpečnostné zásady
- Plugin **nemení** konfiguráciu Velocity, forwarding secret ani autentifikáciu.
- Redis heslo sa nikdy neukladá natvrdo — načítava sa z premennej prostredia `ARVYX_REDIS_PASSWORD`.
- Sieťové správy používajú bezpečnú JSON serializáciu (žiadna Java `ObjectInputStream` deserializácia).

---

## 2. Novinky vo verzii 1.1.3

- **Potvrdený vzdialený matchmaking:** cieľový backend spracuje rezerváciu na hlavnom Bukkit vlákne a odpovie správou `PLAYER_TRANSFER_ACK`; zdroj presunie hráča cez Velocity až po kladnom ACK.
- **Bezpečné zlyhanie:** odmietnutá rezervácia, oneskorený ACK, timeout alebo odpojenie hráča nespustia presun. Čakajúce požiadavky sa čistia po TTL.

## 2.1. Novinky vo verzii 1.1.2

- **Načítanie konfigurácie servera:** `servers/<server-id>.yml` sa automaticky aplikuje ako overlay na `config.yml`; `ARVYX_SERVER_ID` umožňuje zvoliť backend bez úpravy JAR.
- **Redis premenné prostredia:** `ARVYX_REDIS_ENABLED`, `ARVYX_REDIS_HOST`, `ARVYX_REDIS_PORT` a `ARVYX_REDIS_SSL` majú prednosť pred YAML konfiguráciou.
- **Automatické testy:** GitHub Actions zostavuje projekt a spúšťa JUnit testy na Java 25.

## 3. Novinky vo verziách 1.1.0 – 1.1.1

- **Matchmaking a rezervácie miest (`MatchmakingManager`, `Arena`):**
  - `/arvyx join skyrush` a `/arvyx join chaos` vyberajú najlepšiu voľnú lokálnu arénu alebo rezervujú miesto na cieľovom backend serveri (`PLAYER_TRANSFER_REQUEST` s TTL 15s) a presunú hráča cez Velocity (`claimReservationOnJoin` po príchode).
  - `/arvyx leave` a odpojenie hráča (`PlayerLifecycleListener`) bezpečne vyhodnocujú elimináciu/víťaza zápasu pred odstránením dát z arény.
- **Skutočná sieťová komunikácia (`RedisNetworkManager`):**
  - Asynchrónny Redis Pub/Sub na troch kanáloch (`servers`, `arenas`, `messages`), blokujúce odberateľské spojenie bez zbytočných `SocketTimeoutException`, čisté zatvorenie pri vypnutí, resynchronizácia po opätovnom pripojení a bezpečný lokálny fallback pri výpadku.
- **Bezpečný reset a ochrana máp (`ArenaBlockTracker`, `WorldBackupManager`, `ResetManager`, `ArenaBlockListener`):**
  - Sledovanie zmenených blokov, tekutín (`BlockFromToEvent`), výbuchov aj SkyRush Supply Drop truhlíc, ochrana nečinných/resetujúcich sa arén, obnova inventára/efektov/gravitácie a voliteľná obnova sveta zo zálohy.
  - Bezpečné ošetrenie aktívnych zápasov pri vypnutí/reštarte backend servera a uvoľnenie offline `PlayerData` proti úniku pamäte.

---

## 4. Príkazy a oprávnenia

| Príkaz | Popis | Oprávnenie |
|---|---|---|
| `/arvyx join <skyrush\|chaos>` | Pripojenie do voľnej arény | `arvyx.join` (default: true) |
| `/arvyx leave` | Opustenie arény a návrat na lobby | `arvyx.leave` (default: true) |
| `/arvyx` alebo `/arvyx info` | Informácie o plugine, sieti a transporte | `arvyx.admin` |
| `/arvyx reload` | Bezpečné znovunačítanie YAML konfigurácií | `arvyx.reload` |
| `/arvyx arenas` | Zoznam lokálnych arén servera | `arvyx.arena` |
| `/arvyx arena <id>` | Podrobnosti konkrétnej arény | `arvyx.arena` |
| `/arvyx start <id>` | Manuálne spustenie arény | `arvyx.start` |
| `/arvyx stop <id>` | Bezpečné zastavenie arény | `arvyx.stop` |
| `/arvyx reset <id>` | Okamžitý bezpečný reset arény | `arvyx.reset` |
| `/arvyx event <id> <event>` | Aktivácia Chaos udalosti v aréne | `arvyx.event` |

---

## 5. Kompilácia a inštalácia

### Požiadavky
- **Java:** JDK 25
- **Server:** Paper 26.3
- **Voliteľne:** Redis server (pre synchronizáciu medzi 6 backend/lobby servermi)

### Zostavenie projektu a testov

```bash
./gradlew clean build
```

Výsledný self-contained plugin (vrátane relokovaného Redis klienta) sa nachádza v:

```text
build/libs/ArvyxCore-1.1.3.jar
```

### Nasadenie na sieť
1. Nahrajte `build/libs/ArvyxCore-1.1.3.jar` do priečinka `plugins/` na všetkých 6 serveroch (`lobby`, `skyrush-1..3`, `chaos-1..2`).
2. Nastavte na každom serveri premennú `ARVYX_SERVER_ID` na jeho ID (`lobby`, `skyrush-1`, `skyrush-2`, `skyrush-3`, `chaos-1` alebo `chaos-2`). Plugin potom automaticky načíta zodpovedajúci súbor `servers/<server-id>.yml` ako overlay. Ak premennú nenastavíte, použije sa `server.id` z `config.yml`.
3. Nastavte `ARVYX_REDIS_HOST` na adresu Redis servera dostupnú zo všetkých kontajnerov a `ARVYX_REDIS_ENABLED=true`. Voliteľne nastavte `ARVYX_REDIS_PORT` (predvolene 6379), `ARVYX_REDIS_SSL=true` a pri autentifikácii `ARVYX_REDIS_PASSWORD`. `127.0.0.1` je správne iba vtedy, ak Redis skutočne beží v tom istom sieťovom priestore ako daný Paper proces.
4. Na každom backend serveri sa automaticky zaregistrujú iba arény z `arenas.yml`, ktorých `server` zodpovedá jeho `server.id`.
