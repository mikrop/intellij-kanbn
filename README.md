# Kanbn Board pro IntelliJ

Plugin pro IntelliJ IDEA (2026.3+), který zobrazuje [kanbn](https://github.com/basementuniverse/kanbn) board projektu. Board je **jen pro čtení**: úkoly mezi sloupci přesouvají AI agenti přes kanbn CLI, plugin ukazuje aktuální stav.

- Tool window **Kanbn** (dole): sloupce a karty z `.kanbn/index.md` a `.kanbn/tasks/*.md`.
- Na kartě je název, ID, řešitel, štítky, stav (rozpracováno / hotovo), podúkoly, termín a počet komentářů.
- Progress (pole `progress`) se zatím nezobrazuje, protože ho agenti nenastavují. Data se načítají a vykreslení je připravené, zapíná se přepínačem `SHOW_PROGRESS` v `src/main/resources/intellijkanbn/board.html`.
- Kliknutím na kartu se otevře detail: metadata, popis (markdown), podúkoly, vazby, komentáře a historie přesunů.
- Board se obnovuje sám. Změny souborů v kanbn složce hlásí operační systém (Java `WatchService`), takže se zachytí i zápisy agentů mimo IDE a board se překreslí hned po přesunu úkolu.
- Víc boardů v jedné kanbn složce: přepínač v hlavičce.
- Skryté sloupce (`hiddenColumns`) se nezobrazují, v hlavičce je uvedený jejich počet úkolů.

Plugin nevolá kanbn ani Node. Soubory čte sám podle formátu kanbn 2.6 (`docs/index-structure.md`, `docs/task-structure.md`).

## Instalace

Hotový ZIP je přiložený u každého vydání na [GitHubu](https://github.com/mikrop/intellij-kanbn/releases). Ze zdrojů:

```bash
./gradlew buildPlugin
```

V IntelliJ: *Settings > Plugins > ⚙ > Install Plugin from Disk...* a vybrat `build/distributions/intellij-kanbn-<verze>.zip`.

Gradle potřebuje JDK 21, zbytek (toolchain, IntelliJ Platform) si stáhne sám. Testy: `./gradlew test`.

Pro vývoj: `./gradlew runIde -PrunIdeProject=<cesta k projektu>` spustí sandbox IDE s pluginem a otevře daný projekt. Plugin se překládá proti lokální instalaci IntelliJ. Cesta se bere z vlastnosti `localIdePath` (např. v `~/.gradle/gradle.properties`), jinak se použije `%LOCALAPPDATA%/Programs/IntelliJ IDEA Ultimate`. Když lokální instalace není, stáhne se IntelliJ ve verzi `platformVersion`. Kompatibilitu s IDE ověří `./gradlew verifyPlugin`.

## Kanbn CLI pro agenty (Windows)

Oficiální kanbn 2.6.1 má na Windows chybu: `listBoards()` a validace nevidí žádné soubory, protože glob vzory se skládají ze zpětných lomítek. Oprava je ve forku [mikrop/kanbn](https://github.com/mikrop/kanbn), větev `fix-windows-glob`, commit `7446fb96` (PR basementuniverse/kanbn#14). Dokud nebude v upstreamu, instalovat CLI z tohoto commitu:

```bash
npm i -g github:mikrop/kanbn#7446fb96
```

Doporučení pro board řízený agenty:

- Nastavit `startedColumns` a `completedColumns` podle vlastních sloupců. `kanbn init` je zakládá jako `In Progress` a `Done`, a když takové sloupce nejsou, nezapisují se data `started` a `completed`. Podle nich plugin ukazuje *rozpracováno* a *hotovo*.
- ID úkolu se tvoří z názvu a drží diakritiku (`kanbn add -n "Kontrola výstupu"` vytvoří `kontrola-výstupu`).
- Řešitel (`-a`) se zobrazuje na kartě. Je vhodné, aby to byl název agenta.

## Struktura

```
src/main/kotlin/com/github/mikrop/intellijkanbn/
  model/   datové třídy (workspace, board, úkol)
  parse/   čtení markdown souborů kanbn
  watch/   sledování změn souborů (WatchService)
  ui/      tool window, JCEF panel, příprava dat pro stránku
src/main/resources/intellijkanbn/board.html   stránka boardu (HTML + JS, téma z IDE)
src/test/resources/fixture/                  ukázkový board vytvořený kanbn CLI
.github/workflows/                           sestavení (build.yml) a publikace (release.yml)
CHANGELOG.md                                 změny, z nich se tvoří poznámky k vydání
```

## Vydání

1. Změny zapisovat do `CHANGELOG.md` pod `## [Unreleased]`, verzi nastavit v `gradle.properties` (`version`).
2. Po pushi do `main` workflow **Build** (`.github/workflows/build.yml`) sestaví plugin, spustí testy a `verifyPlugin` a založí koncept vydání na GitHubu s poznámkami z `[Unreleased]`.
3. Zveřejněním konceptu (release nebo pre-release) se spustí workflow **Release** (`.github/workflows/release.yml`): doplní verzi do `CHANGELOG.md`, publikuje plugin do JetBrains Marketplace (`publishPlugin`), přiloží ZIP k vydání a otevře pull request s upraveným changelogem.

Verze s příponou (např. `0.2.0-beta.1`) jde do kanálu `beta`, bez přípony do výchozího kanálu.

Tajemství v repozitáři na GitHubu:

- `PUBLISH_TOKEN`: token z JetBrains Marketplace (povinný)
- `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD`: podepisování pluginu (volitelné, bez nich se plugin nepodepíše)

Lokálně jde publikovat přes `./gradlew publishPlugin`, token se vezme z `PUBLISH_TOKEN` nebo z vlastnosti `publishToken` (např. v `~/.gradle/gradle.properties`). První verzi pluginu je potřeba nahrát do Marketplace ručně přes web, `publishPlugin` umí jen aktualizace.
