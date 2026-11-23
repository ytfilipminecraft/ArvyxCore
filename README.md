# ArvyxCore (ARVYX NETWORK)

**ArvyxCore** je modulárny, bezpečnostne orientovaný základný Minecraft Paper plugin pre sieť **ARVYX NETWORK**, navrhnutý pre **Paper 26.3**, **Java 25** a **Gradle Kotlin DSL**.

---

## 1. Architektúra siete a rola pluginu

Sieť ARVYX NETWORK pozostáva z proxy **Velocity (`25565`)** a backend serverov:

- `lobby` (`25566`)
- `skyrush-1` (`25571`), `skyrush-2` (`25572`), `skyrush-3` (`25573`)
- `chaos-1` (`25581`), `chaos-2` (`25582`)

Bezpečnostné zásady:
- Plugin **nemení** konfiguráciu Velocity, forwarding secret ani autentifikáciu.
- Plugin **neotvára** backend porty na verejný internet ani nespúšťa systémové príkazy.
- Presun hráčov na lobby prebieha cez štandardný Velocity/BungeeCord plugin messaging kanál (`BungeeCord` `Connect`) s bezpečným lokálnym fallback teleportom.

---

## 2. Moduly pluginu

- **`CoreModule`** (`sk.arvyx.core.CoreModule`) — inicializácia, správa modulov, konfigurácie, listenerov, príkazov a čisté vypnutie schedulerov.
- **`ArenaManager`** (`sk.arvyx.core.arena`) — registrácia arén a prísne riadený stavový automat (`DISABLED`, `WAITING`, `STARTING`, `INGAME`, `ENDING`, `RESETTING`).
- **`GameManager`** (`sk.arvyx.core.game`) — izolovaný register herných režimov:
  - **SkyRush** (`sk.arvyx.core.game.skyrush`) — rýchly ostrovný mód (3–5 minút), štartovací kit, loot truhlice, Supply Drop na strede, automatické vyhodnotenie víťaza.
  - **Chaos** (`sk.arvyx.core.game.chaos`) — streamovací mód s oddelenými triedami udalostí (`LOW_GRAVITY`, `KNOCKBACK_STICK`, `TNT_RAIN`, `SPEED_BOOST`, `CHAOS_FOG`), cooldownmi a pripraveným rozhraním `StreamEventBridge`.
- **`PlayerManager`** (`sk.arvyx.core.player`) — sledovanie hráčov cez `UUID`, ochrana pred duplicitným priradením do viacerých arén, bezpečný návrat na lobby.
- **`CountdownManager`** (`sk.arvyx.core.countdown`) — odpočítavanie so zvukovými efektmi, upozorneniami a automatickým zrušením pri poklese hráčov pod minimum.
- **`ResetManager`** (`sk.arvyx.core.reset`) — bezpečné čistenie dočasných entít, inventára, efektov a herných nastavení bez nebezpečného mazania priečinkov svetov.
- **`NetworkManager`** (`sk.arvyx.core.network`) — sieťová abstrakcia pripravená na budúcu integráciu Redis / externého API (plne funkčná aj v lokálnom režime bez Redis).

---

## 3. Príkazy a oprávnenia

| Príkaz | Popis | Oprávnenie |
|---|---|---|
| `/arvyx` alebo `/arvyx info` | Informácie o plugine a serveri | `arvyx.admin` |
| `/arvyx reload` | Bezpečné znovunačítanie YAML konfigurácií | `arvyx.reload` |
| `/arvyx arenas` | Zoznam arén a ich stavov | `arvyx.arena` |
| `/arvyx arena <id>` | Podrobnosti konkrétnej arény | `arvyx.arena` |
| `/arvyx start <id>` | Manuálne spustenie arény | `arvyx.start` |
| `/arvyx stop <id>` | Bezpečné zastavenie arény | `arvyx.stop` |
| `/arvyx reset <id>` | Okamžitý bezpečný reset arény | `arvyx.reset` |
| `/arvyx event <id> <event>` | Aktivácia Chaos udalosti v aréne | `arvyx.event` |

---

## 4. Požiadavky a kompilácia

- **Java:** JDK 25
- **Minecraft Server:** Paper 26.3
- **Build:** Gradle Wrapper (`./gradlew`)

### Zostavenie projektu a spustenie testov

```bash
./gradlew clean build
```

Po úspešnom zostavení sa výsledný plugin nachádza v:

```text
build/libs/ArvyxCore-1.0.0.jar
```

### Inštalácia na Paper server

1. Skopírujte `build/libs/ArvyxCore-1.0.0.jar` do priečinka `plugins/` na príslušnom backend serveri (`skyrush-1`, `chaos-1`, atď.).
2. Spustite alebo reštartujte Paper server (vygenerujú sa súbory `config.yml`, `arenas.yml`, `messages.yml`, `games/skyrush.yml`, `games/chaos.yml`).
3. V `plugins/ArvyxCore/config.yml` nastavte `server.id`, `server.role` a `network.lobby-server`.
4. V `plugins/ArvyxCore/arenas.yml` nastavte názvy načítaných svetov a súradnice arén.
5. Konfiguráciu načítajte príkazom `/arvyx reload`.
