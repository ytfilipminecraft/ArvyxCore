# ArvyxCore 1.1.1 (ARVYX NETWORK)

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

## 2. Novinky vo verziách 1.1.0 – 1.1.1

- **Matchmaking a rezervácie miest (`MatchmakingManager`, `Arena`):**
  - `/arvyx join skyrush` a `/arvyx join chaos` vyberajú najlepšiu voľnú lokálnu arénu alebo rezervujú miesto na cieľovom backend serveri (`PLAYER_TRANSFER_REQUEST` s TTL 15s) a presunú hráča cez Velocity (`claimReservationOnJoin` po príchode).
  - `/arvyx leave` a odpojenie hráča (`PlayerLifecycleListener`) bezpečne vyhodnocujú elimináciu/víťaza zápasu pred odstránením dát z arény.
- **Skutočná sieťová komunikácia (`RedisNetworkManager`):**
  - Asynchrónny Redis Pub/Sub na troch kanáloch (`servers`, `arenas`, `messages`), blokujúce odberateľské spojenie bez zbytočných `SocketTimeoutException`, čisté zatvorenie pri vypnutí, resynchronizácia po opätovnom pripojení a bezpečný lokálny fallback pri výpadku.
- **Bezpečný reset a ochrana máp (`ArenaBlockTracker`, `WorldBackupManager`, `ResetManager`, `ArenaBlockListener`):**
  - Sledovanie zmenených blokov, tekutín (`BlockFromToEvent`), výbuchov aj SkyRush Supply Drop truhlíc, ochrana nečinných/resetujúcich sa arén, obnova inventára/efektov/gravitácie a voliteľná obnova sveta zo zálohy.
  - Bezpečné ošetrenie aktívnych zápasov pri vypnutí/reštarte backend servera a uvoľnenie offline `PlayerData` proti úniku pamäte.

---

## 3. Príkazy a oprávnenia

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

## 4. Kompilácia a inštalácia

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
build/libs/ArvyxCore-1.1.1.jar
```

### Nasadenie na sieť
1. Nahrajte `build/libs/ArvyxCore-1.1.1.jar` do priečinka `plugins/` na všetkých 6 serveroch (`lobby`, `skyrush-1..3`, `chaos-1..2`).
2. Pre každý server nastavte príslušnú konfiguráciu podľa šablón v `src/main/resources/servers/<server-id>.yml`.
3. Ak používate Redis s heslom, nastavte systémovú premennú `ARVYX_REDIS_PASSWORD` pred spustením Paper servera.
4. Na každom backend serveri sa automaticky zaregistrujú iba arény z `arenas.yml`, ktorých `server` zodpovedá jeho `server.id`.
