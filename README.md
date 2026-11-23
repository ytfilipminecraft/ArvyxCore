# ArvyxCore 1.1.0 (ARVYX NETWORK)

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

## 2. Novinky vo verzii 1.1.0

- **Matchmaking (`MatchmakingManager`):**
  - `/arvyx join skyrush` a `/arvyx join chaos` vyberajú najlepšiu voľnú lokálnu arénu (alebo presunú hráča cez Velocity na najvhodnejší online backend server), skontrolujú kapacitu a stav, zabránia duplicitnému priradeniu, teleportujú hráča na lobby arény a po dosiahnutí `min-players` automaticky spustia odpočítavanie.
  - `/arvyx leave` bezpečne odstráni hráča z arény, vyhodnotí prípadné ukončenie zápasu alebo zrušenie odpočítavania a vráti hráča na lobby.
- **Skutočná sieťová komunikácia (`RedisNetworkManager`):**
  - Asynchrónny Redis Pub/Sub na troch kanáloch (`servers`, `arenas`, `messages`), periodický heartbeat, detekcia nedostupných serverov po timeout-e a automatický bezpečný fallback do lokálneho režimu pri výpadku Redis.
- **Bezpečný reset SkyRush máp (`ArenaBlockTracker`, `WorldBackupManager`, `ResetManager`):**
  - Dvojitá ochrana proti súbežnému resetu (`activeResets`), obnova zmenených blokov a dočasných stavieb, doplnenie loot truhlíc, čistenie dočasných entít a voliteľná asynchrónna obnova sveta z overenej zálohy.
  - Bezpečné ošetrenie aktívnych zápasov pri vypnutí/reštarte backend servera.

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
build/libs/ArvyxCore-1.1.0.jar
```

### Nasadenie na sieť
1. Nahrajte `build/libs/ArvyxCore-1.1.0.jar` do priečinka `plugins/` na všetkých 6 serveroch (`lobby`, `skyrush-1..3`, `chaos-1..2`).
2. Pre každý server nastavte príslušnú konfiguráciu podľa šablón v `src/main/resources/servers/<server-id>.yml`.
3. Ak používate Redis s heslom, nastavte systémovú premennú `ARVYX_REDIS_PASSWORD` pred spustením Paper servera.
4. Na každom backend serveri sa automaticky zaregistrujú iba arény z `arenas.yml`, ktorých `server` zodpovedá jeho `server.id`.
