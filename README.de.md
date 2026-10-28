# DSH Mobile

**Der vollständige Android-Port von DeepSeek Harness — die offizielle dsh-Engine läuft unverändert in der App-Sandbox. Ohne Root, ohne Termux, ohne PC.**

<!-- 徽章区：左边看"有多少人在用"，右边看"项目是否健康"。 -->
[![Downloads](https://img.shields.io/github/downloads/Soodok/Deepseek-Harness-Local-Android/total?style=for-the-badge&label=downloads&color=2563eb)](https://github.com/Soodok/Deepseek-Harness-Local-Android/releases)
[![Stars](https://img.shields.io/github/stars/Soodok/Deepseek-Harness-Local-Android?style=for-the-badge&color=f59e0b)](https://github.com/Soodok/Deepseek-Harness-Local-Android/stargazers)
[![Release](https://img.shields.io/github/v/release/Soodok/Deepseek-Harness-Local-Android?style=for-the-badge&color=16a34a)](https://github.com/Soodok/Deepseek-Harness-Local-Android/releases/latest)

[![CI](https://github.com/Soodok/Deepseek-Harness-Local-Android/actions/workflows/android-build.yml/badge.svg)](https://github.com/Soodok/Deepseek-Harness-Local-Android/actions/workflows/android-build.yml)
![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B%20~%2016-3ddc84?logo=android&logoColor=white)
![Engine](https://img.shields.io/badge/engine-dsh%200.2.1--rc.2-4b5563)
![Runtime](https://img.shields.io/badge/runtime-bionic%20Node.js-5fa04e?logo=nodedotjs&logoColor=white)
![License](https://img.shields.io/badge/license-MIT-brightgreen)
[![Last commit](https://img.shields.io/github/last-commit/Soodok/Deepseek-Harness-Local-Android?color=64748b)](https://github.com/Soodok/Deepseek-Harness-Local-Android/commits/main)
[![QQ](https://img.shields.io/badge/QQ%E7%BE%A4-1128769016-12b7f5?logo=tencentqq&logoColor=white)](https://github.com/Soodok/Deepseek-Harness-Local-Android#%E5%8F%8D%E9%A6%88)
[![Telegram](https://img.shields.io/badge/Telegram-Join_group-26A5E4?logo=telegram&logoColor=white)](https://t.me/+4VN3pYyH6i8yNDA1)
![i18n](https://github.com/Soodok/Deepseek-Harness-Local-Android/actions/workflows/i18n-check.yml/badge.svg)

<p align="center">
  <img src="docs/promo/dsh-mobile-github.png" alt="DSH Mobile — DeepSeek Harness. Jetzt in deiner Hosentasche." width="100%">
</p>

[English](README_EN.md) · [中文](README.md) · [Deutsch](#einführung)

---

## Einführung

DSH Mobile ist der **vollständige Android-Port von [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)**, dem quelloffenen Agent-Framework von DeepSeek. Eine vollständige Node.js-Agent-Engine läuft in der App-Sandbox und lauscht auf dem Loopback `127.0.0.1` — Sitzungen, Zugangsdaten und Arbeitsbereiche **bleiben auf deinem Smartphone**. Installieren und loslegen; deine Daten verlassen das Gerät nie.

## 🎯 Was es ist · Was es nicht ist

**Es ist**: der **vollständige Android-Port von DeepSeek Harness** (`@deepseek-ai/dsh`). Die Engine ist der offizielle Upstream-Code (festgepinnt auf `0.2.1-rc.2`) — Plugin-System, WebUI und Werkzeugaufrufkette stammen sämtlich von Upstream. Dieses Projekt liefert die Laufzeitumgebung für Android: eine selbstgebaute bionic-Node.js-Laufzeit mit geprüftem Abhängigkeitsabschluss, Keep-Alive über einen Vordergrunddienst, Berechtigungsstufen und das Erweiterungszentrum. **Alles, was dsh am Desktop kann, kann dieses Projekt** — nur eben in der Sandbox des Smartphones.

**Es ist nicht**:

- ❌ **Kein selbst gebautes KI-Agent-Framework** — Agent-Logik, Plugin-System und Kontextverwaltung stammen alle von DeepSeek Harness. Dieses Projekt ist ein **Port**, kein Ersatz, und es konkurriert nicht mit dem Upstream
- ❌ **Keine On-Device-LLM-App** — keine Modellgewichte, keine lokale Inferenz. Modelle werden über den API-Endpunkt erreicht, den du konfigurierst, genau wie bei dsh am Desktop
- ❌ **Kein Telefon-Automations-Agent** — Screen-Reading und Tippen per Accessibility sind **eines der Werkzeuge**, die dem Agenten zur Verfügung stehen, nicht das Produkt selbst
- ❌ **Keine proot-/Ubuntu-Container-Lösung** — kein Linux-Container, kein Distributions-rootfs. Die Node.js-Laufzeit und die gesamte Toolchain liegen als native Android-Binärdateien (bionic) direkt im APK: installieren und loslegen, ohne Container-Initialisierung

In einem Satz: **Du suchst eine Möglichkeit, dsh unter Android ohne Termux zu betreiben? Genau das ist es.**

## ⚡ Gemessene Leistung

Alle Werte stammen aus Tests auf echten Geräten und im Emulator, nicht aus der Theorie:

| Kennzahl | Gemessen | Umgebung |
|---|---|---|
| **Kaltstart bis Engine bereit** | **3,4 s** | OnePlus 15T (Android 16), echtes Gerät |
| **Kaltstart, Mittelklasse** | **5 – 6 s** | Geräte verschiedener Hersteller |
| **Kaltstart, schwächstes Gerät** | **13 s** | Unisoc-Uhr mit **3 GB RAM** |
| **Kaltstart, Emulator** | **2,2 – 2,7 s** | x86_64 / 2,5 GB RAM |
| **Speicher mit parallelen Diensten** | **< 400 MB** | Engine + mehrere Toolchains gleichzeitig |
| **Android-Kompatibilität** | **8.0 → 16** | WebView ≥ Chrome 85 erforderlich |
| **APK-Größe** | **voll ~150 MB / schlank ~24 MB** | Vollversion enthält Node.js; noruntime lädt sie beim ersten Start |
| **Toolchains** | **21 One-Tap-Installationen** | Python/Go/Rust/Clang/OpenJDK/FFmpeg… |

### Warum es so schnell ist

Die meisten Android-Agent-Lösungen können aus einem **strukturellen** Grund nicht
schnell sein: Sie bauen erst eine Linux-Umgebung auf dem Gerät (Termux + apt),
betreiben einen proot-Container (glibc-rootfs, 500 MB – 2 GB) oder liefern einen
eingefrorenen Termux-Snapshot. Alle drei müssen vor dem Engine-Start viel
*vorbereiten* — rootfs entpacken, Pseudo-Dateisysteme mounten, Paketindex aufbauen.

DSH Mobile hat **keine Zwischenschicht**:

| Ansatz | Was zwischen dir und der Engine liegt | Kaltstart |
|---|---|---|
| Termux, manuell | Perl/bash-Boot-Kette + apt + Env-Init | Minuten |
| proot + Ubuntu | Kernel → proot → glibc → Distro → Node | ab 10 s, scheitert auf schwächeren Geräten |
| Eingefrorener Snapshot | rootfs entpacken → Pfade fixen → Start | Dutzende Sekunden |
| **DSH Mobile** | **— direktes `execve` von bionic Node** | **~3 s** |

Die komplette Laufzeit liegt als **Android-native (bionic) Binaries im APK**. Die
Laufzeit muss nicht „booten" — sie ist einfach da. Der Kaltstart macht nur drei
Dinge: entpacken (nur beim ersten Start), Node starten, auf den Loopback-Port warten.

Dass eine Unisoc-Uhr mit 3 GB RAM das überhaupt schafft, ist der Beweis der
Architektur: proot + glibc würde dort nicht einmal das rootfs unterbringen.

## ✨ Was es auf einem Smartphone kann

**Ein echtes PTY, in nativem C geschrieben**
Web-Terminals wirken einfach, sind hier aber die härteste Schicht. Upstream
`dsh-subprocess-local` scheitert zweimal an Android: Der Process-Inspector akzeptiert
nur `linux` / `darwin` / `win32` und wirft `terminal inspection is unsupported on
platform android` (Termux-node meldet `process.platform === "android"`, nicht
`linux`), und `node-pty` hat keine Android-Builds, weshalb der Collector einen Stub
einsetzt, der beim ersten Aufruf wirft. Wir patchen den Inspector so, dass `android`
als linux gilt, und schrieben **581 Zeilen C** (`dsh_pty.c` + `dsh_pty_exec.c`,
`posix_spawn` + `openpty`); node-pty ist ein Shim auf diesen nativen Helfer. Ergebnis:
ein **echtes bash-Terminal** — `top`, `vim` und andere tty-Programme laufen.

**14 Android-Anpassungspatches — eine Desktop-Engine aufs Telefon biegen**
Der unsichtbarste und aufwändigste Teil. Upstream setzt einen Linux-Desktop voraus,
und jede dieser Annahmen ist auf Android ein Absturz: die PTY-Plattformprüfung;
PTC-Worker, die `LD_LIBRARY_PATH` leeren (Android hat kein ldconfig, bionic-node
findet `libz.so.1` nicht) plus Bootstrap-Pfad-Fallback; 14 moderne Web-APIs, die
alten WebViews fehlen; Dateisystem-Pfad- und Rechte-Semantik; `openPath`, das
`xdg-open` / `open` / `start` aufruft; Attachment-URI-Behandlung; bash, das bei
installiertem Termux `/data/data/com.termux/...` liest; `bin/curl` ohne
`LD_LIBRARY_PATH`; und die Toolchain-Variablen, ohne die Python / Perl / Git /
ImageMagick / Rust in PTC stillschweigend ausfallen. Jeder Patch ist **fail-fast**:
Verschiebt ein Upstream-Refactoring einen Anker, **bricht der Build ab** statt zu
warnen — ein fehlender Patch heißt eine kaputte Funktion für Nutzer.

**Runtime-Wechsel mit Two-Phase-Commit**
Ein Runtime-Wechsel darf weder „löschen dann entpacken" sein (hinterlässt ein
Fenster, in dem nichts läuft) noch ein naives Umbenennen aller Top-Level-Einträge
(jede Unterbrechung hinterlässt einen gemischten Alt/Neu-Baum — und die
Vollständigkeitsprüfung erkennt das **nicht**: neues `bin/` mit altem `lib/` kommt
durch, weil nur Ankerdateien und Node-Architektur geprüft werden). Seit v1.4.26
entpacken wir nach `engine.staging`, prüfen es (Anker + Executable-Bit +
**ELF-Architektur**), schreiben ein **`.runtime-swap`-Intent-Journal**, benennen
Einträge einzeln um, behalten `engine.old` bis die Engine erstmals Healthy erreicht,
und erkennen beim nächsten Start einen halbfertigen Wechsel und stellen wieder her.
Das ist Two-Phase-Commit auf einem Dateisystem; vom OEM gekillt zu werden ist auf
Android normal — ohne das ist jedes Runtime-Update ein Glücksspiel.
Siehe [docs/RUNTIME_SWAP.md](docs/RUNTIME_SWAP.md).

**Vollständige Ausführung von Agent-Aufgaben**
Gib dem Agenten Aufgaben: Dateien lesen und schreiben, Shell-Befehle ausführen, Volltextsuche, Projekte verwalten — bionic bash / ripgrep / pnpm / curl sind mit geprüftem ELF-Abhängigkeitsabschluss im APK enthalten. Echte Ausführungskraft, keine reine Chat-Hülle.

**Sofortige Vorschau dessen, was er baut**
Der Agent startet einen lokalen HTTP-Server (mit dem mitgelieferten node) und übergibt dir einen `http://127.0.0.1:<Port>`-Link — antippen zum Live-Vorschauen, ein Tipp zurück zur Startseite. In der Praxis verifiziert mit Mini-Games, statischen Seiten und API-Diensten.

**Agent-Fähigkeiten, die du nach Bedarf skalierst**
Drei Berechtigungsstufen, jederzeit verfügbar — Normal (Sandbox, Standard) deckt den Alltag ab; im Shizuku-Modus kann der Agent Befehle auf adb-Ebene ausführen (Prozessverwaltung / Systemeigenschaften, ohne Root); der Root-Modus gewährt vollen Lese-/Schreibzugriff (doppelte Bestätigung + automatische Sicherung). Optionen, deren Fähigkeit noch nicht bereitsteht, werden automatisch ausgegraut.

**Das Smartphone steuern (nicht „blind")**
Sobald du den Accessibility-Dienst aktivierst, kann der Agent **Bildschirminhalte lesen** (Text + Koordinaten) und gezielt nach Text oder Koordinaten tippen, um andere Apps zu automatisieren. (Accessibility muss in den Systemeinstellungen manuell eingeschaltet werden.)

**Push-Benachrichtigung bei erledigten Aufgaben**
Der Agent verschickt eine Android-Systembenachrichtigung, wenn eine lange Aufgabe fertig ist — so verpasst du im Hintergrund nichts.

**Daten bleiben strikt lokal**
Die Engine lauscht ausschließlich auf `127.0.0.1`; Sitzungen, Zugangsdaten und Arbeitsbereiche liegen im privaten Verzeichnis der App — beim Gerätewechsel oder Deinstallieren verschwinden sie genau dorthin, wo du es entscheidest.

**Selbstheilung, wenn etwas kaputtgeht**
Fehlerhafte Plugin-Konfigurationen werden automatisch auf den letzten gesunden Snapshot zurückgesetzt; die Engine startet nach einem Absturz mit exponentiellem Backoff neu; ein Vordergrunddienst hält lange Aufgaben vor der Systembereinigung am Leben.

**Erweiterungszentrum: Umgebungen per One Tap**
Das integrierte Erweiterungszentrum bietet **19 Umgebungserweiterungen** — Python, Go, Rust, Clang, OpenJDK, Git, Ruby, PHP, Perl, Lua, SQLite, FFmpeg, ImageMagick, OpenSSH, ADB, Vim und mehr — per One Tap herunterladen (und auch wieder deinstallieren), mit rot/gelb/grün Zustandsverwaltung und live Fortschrittsbalken in der Zeile. Direktverbindung zu chinesischen Mirrors (TUNA → USTC → BFSU → Termux offiziell, mit automatischem Failover), automatische Auflösung des Abhängigkeitsabschlusses, SHA-256-Prüfung und atomares Veröffentlichen. Nach der Installation wird die Shebang-Interpreter-Kette automatisch repariert (`sh`/`env`, sowie absolute Auflösung von extension-übergreifendem `perl`/`python`), sodass Skripte wie `env python3` auch ohne PATH laufen. Jede Zeile bietet **⟳ Neu installieren** (überschreibender Neu-Download, der historische Teilinstallationen repariert); die Installationsprotokolle erfassen die Eintragsanzahl pro Paket und prüfen, ob alle deklarierten Executables vorhanden sind. Die `bin`-Abhängigkeiten aller 19 Erweiterungen werden von `scripts/audit-extension-closures.py` **auf ELF-Ebene auditiert** (jede `NEEDED`-Bibliothek muss vom Abhängigkeitsabschluss abgedeckt sein, sodass Lücken in den Upstream-Metadaten früh auffallen).

**WebUI vom Computer öffnen (LAN)**
Einstellungen → Erweiterungen → **LAN-WebUI-Zugriff**. Nach dem Aktivieren öffnet ein Browser im selben Netzwerk `http://<Handy-IP>:3090` und erhält die **native dsh-WebUI, die auf dem Handy läuft** — dieselbe Oberfläche, keine Nachbildung. Jedes Mal wird ein zufälliger **6-stelliger Code** erzeugt (in die Zwischenablage kopiert, einmal angezeigt), und eine dauerhafte Benachrichtigung mit **Stopp**-Schaltfläche bleibt in der Statusleiste; Stoppen beendet den Proxy-Prozess wirklich (auch im Normalmodus). Der Proxy patcht nur seine eigenen Antworten (Secure-Context-APIs, Loopback-Prüfungen, `/api`-Same-Origin, WebSocket-Upgrades) und ändert keine Engine-Dateien. Für Terminal oder Dateitransfer gibt es weiterhin SSH über die OpenSSH-Erweiterung. Siehe [docs/LAN_WEBUI.en.md](docs/LAN_WEBUI.en.md).

**Community-Plugins per One Tap**
Einstellungen → Erweiterungen → **Community-Plugins**: dsh-Ökosystem-Plugins auf npm suchen und mit einem Tipp installieren bzw. deinstallieren — komplett in der App. Ergebnisse werden auf Kompatibilität gefiltert, Installationen zeigen Fortschritt und Fehlergrund, und Plugins erscheinen sofort im Plugin-Panel der WebUI. Beide Stores ergänzen sich: **Plugins erweitern die Fähigkeiten des Agenten, Erweiterungen liefern die Kommandozeilenumgebung.**

**Kompilieren auf dem Smartphone**
Die Toolchains Clang / Go / Rust / Java / Ruby sind auf echten Geräten verifiziert: Kernel-Header (ndk-sysroot) sowie CPATH / LIBRARY_PATH / RUSTFLAGS / GOTMPDIR werden automatisch injiziert, sodass `clang hello.c -o hello && ./hello` einfach funktioniert; zusammen mit der namensbasierten Prozessverwaltung `psx`/`killx` und einem binärsicheren `curl` erledigt der Agent echte Entwicklungsarbeit auf dem Smartphone. **Parallele Dienste bleiben unter 400 MB Speicherbedarf.**

**Agent erweitert sich selbst**
Der Agent nutzt nicht nur das Erweiterungszentrum, sondern handelt eigenständig: Er installiert in einer Sitzung Umgebungen über die lokale Bridge und aktiviert sie automatisch (und erinnert dich anschließend daran, die Engine neu zu starten). Im Root-Modus hat er sich sogar die Android-SDK-Kommandozeilenwerkzeuge selbst installiert.

## 📚 Funktionsdetails

Zu jeder Fähigkeit gibt es ein eigenes Dokument (Design-Abwägungen, Messwerte, Fehlersuche):

| Dokument | Inhalt |
|---|---|
| [Barrierefreiheit & Gerätesteuerung](docs/ACCESSIBILITY.en.md) | Lesen/Tippen/Eingeben/Wischen über 21 Primitive; mehrschichtige Vision, Verifikation pro Schritt; gemessen ~1,45 s/Schritt |
| [Designnotizen zur geschlossenen Schleife](docs/LOCAL_CLOSED_LOOP.md) | Design und Entwicklung der Entscheidungs-Engine auf dem Gerät |
| [UI-Plugins für schmale Bildschirme](docs/NARROW_UI.en.md) | Seitenleiste über volle Breite / Einstellungen neu angeordnet / rechtes Panel bildschirmfüllend |
| [LAN-WebUI](docs/LAN_WEBUI.en.md) | Reverse-Proxy, 6-stelliger Code, dauerhafte Erinnerung und Stopp-Kette |
| [Spracheingabe](docs/VOICE_INPUT.en.md) | zwei Erkennungskanäle, Sende-Deduplizierung, Panel-Parking, bekanntes Problem |
| [Desktop-Pet](docs/pet/README.md) | plugin-gesteuertes System-Overlay: Einrichtung und Grenzen ([Anleitung](docs/pet/PET_GUIDE.md)), Bridge und Interna ([Architektur](docs/pet/PET_ARCHITECTURE.md)), Funktionsumfang und nächste Schritte ([Roadmap](docs/pet/PET_ROADMAP.md)) |
| [Benachrichtigungen & Hintergrund](docs/NOTIFICATIONS.en.md) | KI-Fortschritt, Aufgabenende, LAN-Erinnerung, sauberes Beenden |
| [Versionshinweise (Archiv)](docs/release-notes/) | `RELEASE_NOTES_*.md` je Version (ab v1.3.0) außerhalb des Repo-Roots archiviert |

## ⛔ Grenzen (bitte beachten)

- **WebView muss Chrome 85+ sein (ab 2020-06)**: Die App selbst unterstützt Android 8.0 → 16, aber die Oberfläche wird im System-WebView gerendert, und das Engine-Frontend nutzt moderne Syntax (`??=`, Klassenfelder) — ein **Parse-Zeit**-Merkmal, das Polyfills nicht ersetzen können. Geräte mit einem System-WebView älter als Chrome 85 zeigen eine leere Seite (das Log lässt sich in den Einstellungen exportieren: es protokolliert den `SyntaxError` und die WebView-Version). **Unter Android 8.0+ ist der WebView eine unabhängig aktualisierbare Systemkomponente** (Google Play / Hersteller-Stores), ein Update auf 85+ genügt also; die wenigen chinesischen ROMs, deren WebView sich nie aktualisieren lässt, sind eine bekannte Einschränkung.
- **Keine Desktop-Umgebung**: Linux-GUI-Desktop-Apps können nicht laufen; visuelle Ergebnisse werden über lokales HTTP + den integrierten WebView angezeigt
- **Vorinstallierte Toolchain ist nur node/bash**: Clang, Python, Go und 16 weitere Umgebungen kommen über das integrierte **Erweiterungszentrum** per One Tap, oder der Agent installiert sie selbst (im Root-Modus mit dem Android SDK verifiziert)
- **Tippen ist „halbblind"**: Das Screen-Reading arbeitet auf dem Accessibility-Knotenbaum (Text + Koordinaten + Klickbarkeit) und ist auf rein grafischen oder Spiel-Bildschirmen wirkungslos; komplexe UI-Automatisierung bleibt begrenzt
- **Android 13+ verlangt eine Bestätigung beim Aktivieren von Accessibility**: Bei per Sideload installierten Apps zeigt das System einen Dialog „Vollständige Kontrolle über dein Gerät erlauben", der von Hand bestätigt werden muss; das Schreiben der Einstellung über adb wird vom System zurückgesetzt. Folge der Anleitung in der App in die System-UI.
- **Lange Aufgaben sind nicht unsterblich**: Der Vordergrunddienst vermeidet die Systembereinigung so weit wie möglich, aber ein erzwungenes Beenden oder ein extremer Energiesparmodus kann sie dennoch unterbrechen (die Engine startet automatisch neu; laufende Aufgaben müssen neu beauftragt werden)
- **Rechteausweitung birgt Risiken**: Im Root-Modus hat die KI vollen Lese-/Schreibzugriff auf das Gerät, und Fehlbedienungen können das System beschädigen — siehe Haftungsausschluss unten

## 🌟 Das kann die Termux-Route nicht bieten

Derselbe dsh, andere Auslieferungsform — und jeder der folgenden Punkte ist Engineering, das dieses Projekt selbst gebaut hat. Genau deshalb heißt es **Port** und nicht Repack:

**Versionsübergreifende Kompatibilität, schon bezahlt**
Die App selbst unterstützt Android 8.0 → 16; das Rendering hängt vom System-WebView ab und **erfordert Chrome 85+ (2020-06)** — das Engine-Frontend nutzt `??=` und Ähnliches, ein Parse-Zeit-Merkmal, das Polyfills nicht ersetzen können (siehe Grenzen). Da viele chinesische ROMs ihren WebView nie aktualisieren können, injiziert der Build ein Polyfill ins Frontend (13 moderne APIs, darunter `AbortSignal.any` / `Promise.withResolvers` / `URL.parse`) und senkt die API-Untergrenze von Chrome 126 auf 85; jede `.so` ist **16-KB-seitenausgerichtet** und läuft damit auch auf Geräten mit neuerem Kernel. Auf der Termux-Route ist jedes davon ein Risiko pro Gerät.

**Plugins sofort nutzbar — ohne vorher Compile-Ingenieur zu spielen**
dshs „alles ist ein Plugin"-Architektur bleibt erhalten: Hot-Loading von Plugins und Sitzungspersistenz (JSONL) funktionieren normal. Die von Plugins benötigten Sprachumgebungen (Python / Go / Rust / Clang / OpenJDK…) ergänzt das Erweiterungszentrum mit **19 One-Tap-Installationen**, wobei Abhängigkeitsabschlüsse in der CI vorab aufgelöst und auf ELF-Ebene geprüft werden. Auf der Termux-Route ist das Scheitern nativer Module wie `sharp` / `koffi` / `node-pty` der Normalfall — die Community unterhält dafür eigens Projekte mit vorcompilierten Modulen.

**Die Engine heilt sich selbst; du musst keine Linux-Logs lesen**
Fehlerhafte Plugin-Konfiguration → automatisches Rollback auf den letzten gesunden Snapshot; hilft das Rollback nicht → der zweistufige Guardian versetzt das System in den sicheren Modus (defekte Konfiguration archivieren, mit leerer Konfiguration starten), alles ist wiederherstellbar; Engine-Absturz → automatischer Neustart mit exponentiellem Backoff. Auf der Termux-Route ist diese ganze Schicht gleichbedeutend mit „Logs selbst lesen und selbst reparieren".

**Bleibt im Hintergrund am Leben und meldet sich bei fertigen Aufgaben**
Ein specialUse-Vordergrunddienst plus ein Supervisor mit exponentiellem Backoff fangen die Systembereinigung ab: Lange Aufgaben laufen auch mit ausgeschaltetem Display oder im Hintergrund weiter, und bei Abschluss geht eine Systembenachrichtigung raus. Bei Termux ist das Überleben aggressiver Energiesparrichtlinien chinesischer ROMs für jeden Nutzer ein eigenes Rätsel.

## 🆚 Routen, um einen Agent unter Android zu betreiben

| | Termux, manuell | Termux, Ein-Klick-Skript | proot + Ubuntu | APK-Snapshot-Bundle | **DSH Mobile** |
|---|---|---|---|---|---|
| Installationserlebnis | Termux installieren, Umgebung einrichten, Abhängigkeiten installieren | Das Skript macht alles | Container und Distribution installieren | Installieren und fertig | **Installieren und fertig** |
| Laufzeit | Live-Umgebung (erweiterbar) | Live-Umgebung (erweiterbar) | glibc im Container (erweiterbar) | Eingefrorener Snapshot | **Selbstgebaute bionic-Komplettmenge, in der CI gesammelt und geprüft** |
| Lizenzkonformität | — | — | — | ⚠️ Snapshot bündelt GPL-Komponenten; Konformität fraglich | **Nur MIT/BSD/ISC/Zlib-Komponenten** |
| Hintergrundzuverlässigkeit | Hängt vom Termux-Sitzungs-Keep-Alive ab | Wie links | Wie links | Watchdog mit Gewalt | **specialUse-Vordergrunddienst + Supervisor mit exponentiellem Backoff** |
| Berechtigungsstufen | Keine | Keine | Keine | Keine | **Dreistufige Modi + su-Gate + Shizuku-adb-Bridge** |
| Build-Engineering | — | Teilweise reproduzierbar | — | Keine CI, nicht aus dem Quelltext reproduzierbar | **Dual-Architektur-CI: sammeln → Abschluss prüfen → 16-KB-Ausrichtung → APK** |
| Erststart | Minuten nach manueller Einrichtung | Minuten nach dem Skript | Minuten Container-Initialisierung | Minuten zum Entpacken des Snapshots | **Kaltstart < 10 s** (auf Gerät gemessen) |
| Alter WebView | — | — | — | — | **Polyfill-Injektion** (API-Untergrenze 126 → 85) |
| Termux-Abhängigkeit | Termux-App nötig | Termux-App nötig | Termux-App nötig | Snapshot *ist* Termux | **Keine** (fest verdrahtete Pfade umgelegt) |
| Selbstheilung | Manuelle Reparatur | Manuelle Reparatur | Manuelle Reparatur | Watchdog mit Gewalt | **Konfigurations-Rollback + sicherer Modus + Speicher-Selbsttest** |
| Umgebungserweiterungen | Manuell, erweiterbar | Manuell, erweiterbar | apt, erweiterbar | Eingefroren, nicht erweiterbar | **19 One-Tap-Erweiterungen + Selbstinstallation durch den Agenten, offizielle Icons, Dreifachstatus** |
| Versionsübergreifende Kompatibilität | Pro Gerät ausprobieren | Wie links | Wie links | Wie links | **8.0→16 unterstützt + WebView-Polyfill + 16-KB-Ausrichtung** |
| Plugins sofort nutzbar | Native Module einzeln bekämpfen | Hängt von der Patch-Abdeckung des Skripts ab | Wie links | Zum Buildzeitpunkt eingefroren | **In der CI vorab aufgelöster Abhängigkeitsabschluss + 19 One-Tap-Erweiterungen** |

> Diese Routen ersetzen einander nicht: Termux-Lösungen **bauen eine Linux-Umgebung** unter Android und betreiben dsh darin (Vorteil: live und per pkg/apt frei erweiterbar); dieses Projekt **baut die von dsh benötigte Laufzeit direkt ins APK** (Vorteil: installieren und loslegen, keine externe Abhängigkeit). **Beide Routen betreiben dasselbe dsh** — wähle danach, welchen Einrichtungsaufwand du für eine Live-Umgebung zahlen willst.
>
> 📄 Eine ausführliche Analyse aller fünf Routen — Mechanik, Abwägung und Auswahl — findest du in **[docs/android-agent-routes.md](docs/android-agent-routes.md)** ([中文](docs/android-agent-routes.zh.md)).

## Berechtigungsmodi

| | Normal | Shizuku | Root |
|---|---|---|---|
| Identität der Engine | App-Sandbox | App-Sandbox | uid 0, ganzes Gerät |
| Mächt des Agenten | Befehle innerhalb der Sandbox | + Befehle auf adb-Ebene (`shz`) | Voller Lese-/Schreibzugriff |
| Voraussetzung | Keine | [Shizuku](https://shizuku.rikka.app/) installieren und starten | Gerootetes Gerät |
| Schutzmechanismen | su-Gate blockiert Rechteausweitung | su-Gate blockiert Rechteausweitung | Doppelte Hochrisiko-Bestätigung + automatische Sicherung vor dem Start |

Normaler Modus ist der Standard; Optionen, deren Fähigkeit noch nicht bereitsteht, werden ausgegraut und sind nicht wählbar; nach einem Wechsel startet die Engine automatisch neu.

## 📦 Installation

**Release herunterladen (empfohlen)**: Hol dir die APK von [Releases](https://github.com/Soodok/Deepseek-Harness-Local-Android/releases) (für Smartphones `arm64-v8a` wählen; aktuell **v1.4.20**).

> 📦 **Welche Datei?**
> - **Vollversion** (ohne `-noruntime`): enthält die Node.js-Laufzeit (~150–230 MB), offline nutzbar.
> - **noruntime** (`*-noruntime.apk`): deutlich kleiner; lädt die Laufzeit beim ersten Start über den Link in `MANIFEST.json`. Standardmäßig über den `gh-proxy.org`-Präfixproxy (direkte GitHub-Downloads sind in einigen Regionen unzuverlässig); änderbar unter Einstellungen → Erweiterungen → Runtime-Download-Proxy, inklusive direkter Verbindung. App-Updates laden die Laufzeit nicht erneut.
>
> Beide enthalten das integrierte SSH-Plugin; Laufzeit-Updates gibt es unter „Über → Runtime“.
> `-debug`-Builds sind nur zum Testen; Release und Debug lassen sich nicht gegenseitig installieren.

**Aus dem Quelltext bauen** (JDK 17 + Android SDK, NDK r26+, CMake 3.22.1):

```bash
# Linux/CI: einheitliche Runtime-Pipeline
./scripts/build-runtime.sh --abi arm64-v8a --out dist/runtime-arm64-v8a.zip

# Vollversion
gradle assembleDebug -Ppackage=full -PruntimeZip="$PWD/dist/runtime-arm64-v8a.zip" \
  -PhardsshDir="$PWD/dist/hardssh"
# noruntime
gradle assembleDebug -Ppackage=noruntime -PhardsshDir="$PWD/dist/hardssh"
```

Alternativ: Forke das Repository und lasse den Workflow **android-build** auf GitHub Actions in der Cloud ein APK bauen.

**Systemvoraussetzungen**: Android 8.0+ (**WebView ≥ Chrome 85**) · arm64-v8a / x86_64 · **1,2 GB+ freier Speicher empfohlen** für ein gestuftes Runtime-Update (Vollversion ~150–230 MB; Laufzeit entpackt ~494 MB).

## 🚀 Schnellstart

1. Wähle beim ersten Start Anzeigeausrichtung und Berechtigungsmodus (im Zweifel **Normal**)
2. Warte, bis die Laufzeit entpackt (mit echter Fortschrittsanzeige) und die Engine startet
3. Starte ein Gespräch und gib dem Agenten Aufgaben; das Zahnradsymbol öffnet die Einstellungen; tippe auf jeden `127.0.0.1`-Link, den der Agent dir gibt, um sein Ergebnis anzusehen
4. Spracheingabe und das schwebende Fenster sind immer verfügbar — kein Schalter nötig
5. Damit der Agent den Bildschirm bedienen darf: aktiviere den Accessibility-Dienst **DSH Screen Tap** in den Systemeinstellungen (unter Android 13+ fragt das System „Vollständige Kontrolle über dein Gerät erlauben" — tippe auf **Allow**)

## ❓ FAQ

**Wird Root benötigt?** Nein. Der Normal-Modus deckt die überwältigende Mehrheit der Anwendungsfälle ab; Root und Shizuku sind optionale Ausbaustufen.

**Wie verhält sich das zum Termux-Ansatz?** Parallele Routen, kein Ersatz. Die Termux-Route **baut eine Linux-Umgebung** unter Android und betreibt dsh darin; dieses Projekt **baut die Laufzeit ins APK** (Vollversion) oder lädt sie beim ersten Start (noruntime) — **beide betreiben dasselbe dsh**.

**Ist der Agent selbst nachimplementiert?** Nein. Agent-Logik, Plugin-System und WebUI stammen alle vom offiziellen [`@deepseek-ai/dsh`](https://github.com/deepseek-ai/deepseek-harness); dieses Projekt liefert nur die Laufzeitumgebung für Android (bionic-Node.js-Laufzeit, Vordergrunddienst, Berechtigungsstufen, Erweiterungszentrum). Deshalb wird es als **Port** und nicht als Framework bezeichnet.

**Werden meine Daten hochgeladen?** Engine, Sitzungen und Arbeitsbereich liegen vollständig lokal; ob Daten das Gerät verlassen, hängt vom von dir konfigurierten Modell-Dienstendpunkt ab.

**Warum ist die APK groß?** Die Vollversion enthält die vollständige bionic-Node.js-Laufzeit (~494 MB entpackt), den Termux-Toolchain-Abschluss und dshs Abhängigkeitsbaum. Seit v1.4.20 gibt es zusätzlich ein **noruntime-Paket** ohne Laufzeit: App-Updates bleiben klein, die Laufzeit wird beim ersten Start geladen und kann separat aktualisiert werden.

**Wie zeigt mir der Agent eine Webseite?** Bitte ihn, einen lokalen HTTP-Server zu starten, und er übergibt dir einen `http://127.0.0.1:<Port>`-Link; tippe darauf, um die Vorschau zu öffnen.

**Warum kann ich nicht tippen?** Android verlangt, dass Accessibility-Dienste in den Systemeinstellungen manuell aktiviert werden; die Schaltfläche in der App bringt dich lediglich dorthin. Unter Android 13+ erscheint bei der ersten Aktivierung zusätzlich ein Systemdialog („Vollständige Kontrolle über dein Gerät erlauben"), der mit **Allow** bestätigt werden muss.

## ⚠️ Haftungsausschluss

> **Bitte lies diesen Abschnitt vor der Nutzung sorgfältig.**

1. Diese Software wird ohne jede ausdrückliche oder stillschweigende Gewährleistung „wie sie ist" bereitgestellt. Der Autor haftet nicht für direkte oder indirekte Schäden aus der Nutzung, dem Missbrauch oder der Unmöglichkeit der Nutzung dieser Software.
2. **Im Root-Modus läuft die Engine mit höchsten Rechten (uid 0), und von der KI erzeugte Befehle haben vollen Lese-/Schreibzugriff auf das gesamte Gerät.** Die KI kann fehlerhafte, unerwartete oder zerstörerische Aktionen erzeugen — einschließlich, aber nicht beschränkt auf das Löschen von Systemdateien, das Beschädigen von Partitionen oder ein Gerät, das nicht mehr startet. **Jede durch solche Aktionen entstandene Geräteschädigung, Datenverlust oder Gewährleistungsverlust liegt vollständig beim Nutzer; der Autor übernimmt keinerlei Haftung.**
3. Im Shizuku-Modus kann der Agent Operationen auf adb-Ebene ausführen, die ein ähnliches Risiko versehentlicher Schäden tragen; bitte beachte dies und entscheide selbst.
4. Nutze diese Software ausschließlich auf Geräten, **die dir gehören oder deren Kontrolle dir ausdrücklich erlaubt wurde**. Die Folgen der Nutzung auf nicht autorisierten Geräten oder für rechtswidrige Zwecke liegen vollständig beim Nutzer.
5. Root- und Shizuku-Modus sind optional. Ohne sie bleibt der Agent strikt in der App-Sandbox eingeschlossen. **Wenn du keinerlei Risiko übernehmen willst, bleibe im Normal-Modus.**

**Die Installation dieser App oder das Aktivieren eines Modus mit erweiterten Rechten gilt als deine Bestätigung, dass du das Vorstehende gelesen, verstanden und akzeptiert hast.**

## Feedback

Fehler gefunden oder eine Funktion vorgeschlagen? Eröffne ein [Issue](https://github.com/Soodok/Deepseek-Harness-Local-Android/issues). Für Absturzberichte bitte die `logcat`-Ausgabe oder das in der App zugängliche Engine-Log anhängen.

**QQ-Gruppe (Chinesisch): `1128769016`** — Fragen zur Nutzung, Versionsneuigkeiten und Plugin-Empfehlungen.

**[Telegram-Gruppe](https://t.me/+4VN3pYyH6i8yNDA1)** — gleicher Zweck, besser erreichbar ausserhalb Chinas.

Fehler bitte weiterhin als Issue melden, damit sie nicht untergehen.

## 🙏 Mitwirkende

Dieses Projekt ist keine Ein-Personen-Arbeit. Folgende Beiträge sind im Hauptzweig enthalten:

| Mitwirkende/r | Beitrag |
|---|---|
| [@skynit](https://github.com/skynit) | Fehlerhafte Darstellung des Anmelde-/„Loslegen"-Dialogs behoben (PR #30): Ursache war die Viewport-Skalierung gegenüber veralteten Compositor-Ebenen; Lösung: Viewport unter dem Splash finalisieren + vollflächige Neurasterisierung |
| @henryz78 | Behoben: Engine blieb nach einem Neustart dauerhaft hängen (v1.4.9). Fand, dass das `stopping`-Flag in `start()` nie zurückgesetzt wurde, und zusätzlich die Race-Condition, bei der eine verschluckte `CancellationException` den alten Überwachungsloop den Zustand des neuen überschreiben lässt. Wegen Netzwerkproblemen als Quellcode-Archiv eingereicht |

## Lizenz

[MIT](LICENSE). Die Laufzeitkomponenten behalten ihre jeweiligen Original-Lizenzen (MIT / BSD / ISC / Zlib); `@deepseek-ai/dsh` gehört DeepSeek AI.

Dies ist ein unabhängiges Community-Projekt und steht in keiner Verbindung zu DeepSeek.
