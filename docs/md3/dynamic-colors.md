SOURCE: https://developer.android.com/develop/ui/views/theming/dynamic-colors

Ermögliche Nutzern, ihre Farbgebung in deiner App zu personalisieren  |  Views  |  Android Developers
Zum Hauptinhalt springen
/
English
Deutsch
Español – América Latina
Français
Indonesia
Italiano
Polski
Português – Brasil
Tiếng Việt
Türkçe
Русский
עברית
العربيّة
فارسی
हिंदी
বাংলা
ภาษาไทย
中文 – 简体
中文 – 繁體
日本語
한국어
Android Studio
Anmelden
Develop
Core areas
UI
Views
Google verwendet KI-Technologie, um Inhalte in Ihre bevorzugte Sprache zu übersetzen. KI-Übersetzungen können Fehler enthalten.
Android Developers
Develop
Core areas
UI
Views
Ermögliche Nutzern, ihre Farbgebung in deiner App zu personalisieren
Mit Sammlungen den Überblick behalten
Sie können Inhalte basierend auf Ihren Einstellungen speichern und kategorisieren.
Compose ausprobieren
Jetpack Compose ist das empfohlene UI-Toolkit für Android. Hier erfahren Sie, wie Sie in Compose mit dynamischen Farben arbeiten.
Dynamische Farben – Material Design 3 →
Mit der Funktion „Dynamische Farben“, die mit Android 12 eingeführt wurde, können Nutzer ihre Geräte so personalisieren, dass sie farblich auf das Farbschema ihres persönlichen Hintergrunds oder auf eine ausgewählte Farbe in der Hintergrundauswahl abgestimmt sind.
Sie können diese Funktion nutzen, indem Sie die DynamicColors API hinzufügen, die dieses Design auf Ihre App oder Aktivität anwendet, um Ihre App für den Nutzer zu personalisieren.
Abbildung 1. Beispiele für tonale Farbschemata, die von verschiedenen Hintergründen abgeleitet wurden
Auf dieser Seite finden Sie eine Anleitung zum Implementieren von dynamischen Farben in Ihrer App. Diese Funktion ist auch separat für Widgets und adaptive Symbole verfügbar, wie weiter unten auf dieser Seite beschrieben.
Du kannst auch das Codelab ausprobieren.
Hinweis: Die Informationen auf dieser Seite setzen ein Verständnis von Stilen, Farben, Attributen und Designs unter Android voraus. Weitere Informationen finden Sie unter Stile und Designs.
So erstellt Android Farbschemas
Android führt die folgenden Schritte aus, um Farbschemas aus dem Hintergrundbild eines Nutzers zu generieren.
Das System erkennt die Hauptfarben im ausgewählten Hintergrundbild und extrahiert eine Quellfarbe.
Das System verwendet diese Quellfarbe, um fünf Schlüsselfarben zu extrapolieren, die als Primär, Sekundär, Tertiär, Neutral und Neutrale Variante bezeichnet werden.
Abbildung 2. Beispiel für die Extraktion der Quellfarbe aus einem Hintergrundbild und die Extraktion von fünf Schlüsselfarben
Das System interpretiert jede Schlüsselfarbe in eine Farbpalette mit 13 Farbtönen.
Abbildung 3. Beispiel für die Generierung einer bestimmten Farbpalette
Das System verwendet diesen einzelnen Hintergrund, um fünf verschiedene Farbschemas abzuleiten, die die Grundlage für alle hellen und dunklen Designs bilden.
Anzeige von Farbvarianten auf dem Gerät eines Nutzers
Nutzer können ab Android 12 Farbvarianten aus Farben auswählen, die aus dem Hintergrundbild extrahiert wurden, und verschiedene Designs auswählen. In Android 13 wurden weitere Varianten hinzugefügt. Ein Nutzer mit einem Pixel Smartphone mit Android 13 würde beispielsweise eine Variante aus den Einstellungen für Hintergrund & Stil auswählen, wie in Abbildung 4 dargestellt.
Abbildung 4. Farbvarianten in den Hintergrundeinstellungen auswählen (auf einem Pixel-Gerät angezeigt)
In Android 12 wurde die Variante Tonal Spot hinzugefügt, gefolgt von den Varianten Neutral, Vibrant Tonal und Expressive in Android 13. Jede Variante hat ein eigenes Rezept, das die Ausgangsfarben des Hintergrunds eines Nutzers durch Lebendigkeit und Drehung des Farbtons transformiert. Das folgende Beispiel zeigt ein einzelnes Farbschema, das durch diese vier Farbvarianten ausgedrückt wird.
Abbildung 5. Beispiel für die Darstellung verschiedener Farbvarianten auf einem einzelnen Gerät
Ihre App verwendet weiterhin dieselben Tokens, um auf diese Farben zuzugreifen. Weitere Informationen zu Tokens finden Sie auf dieser Seite unter Design mit Tokens erstellen.
Erste Schritte mit Ansichten
Sie können die dynamische Farbe auf App- oder Aktivitätsebene anwenden. Rufen Sie dazu applyToActivitiesIfAvailable() auf, um einen ActivityLifeCycleCallbacks für Ihre App zu registrieren.
Kotlin
class MyApplication: Application() {
override fun onCreate() {
DynamicColors.applyToActivitiesIfAvailable(this)
}
}
Java
public class MyApplication extends Application {
@Override
public void onCreate() {
super.onCreate();
DynamicColors.applyToActivitiesIfAvailable(this);
}
}
Fügen Sie als Nächstes das Design zu Ihrer App hinzu.
<style
name="AppTheme"
parent="ThemeOverlay.Material3.DynamicColors.DayNight">
...
</style>
Design mit Tokens erstellen
Dynamic Color nutzt Design-Tokens, um die Zuweisung von Farben zu verschiedenen UI-Elementen zu optimieren und konsistenter zu gestalten. Mit einem Design-Token können Sie verschiedenen Elementen einer Benutzeroberfläche semantisch Farbrollen zuweisen, anstatt einen festen Wert. Dadurch wird das Farbsystem Ihrer App flexibler, skalierbarer und konsistenter. Das ist besonders nützlich, wenn Sie helle und dunkle Designs sowie dynamische Farben entwerfen.
Die folgenden Snippets zeigen Beispiele für helle und dunkle Designs sowie eine entsprechende Farb-XML nach dem Anwenden dynamischer Farb-Tokens.
Helles Design
Themes.xml
<resources>
<style name="AppTheme" parent="Theme.Material3.Light.NoActionBar">
<item name="colorPrimary">@color/md_theme_light_primary</item>
<item name="colorOnPrimary">@color/md_theme_light_onPrimary</item>
<item name="colorPrimaryContainer">@color/md_theme_light_primaryContainer</item>
<item name="colorOnPrimaryContainer">@color/md_theme_light_onPrimaryContainer</item>
<item name="colorError">@color/md_theme_light_error</item>
<item name="colorOnError">@color/md_theme_light_onError</item>
<item name="colorErrorContainer">@color/md_theme_light_errorContainer</item>
<item name="colorOnErrorContainer">@color/md_theme_light_onErrorContainer</item>
<item name="colorOnBackground">@color/md_theme_light_onBackground</item>
<item name="colorSurface">@color/md_theme_light_surface</item>
<item name="colorOnSurface">@color/md_theme_light_onSurface</item>
…..
</style>
</resources>
Dunkles Design
Themes.xml
<resources>
<style name="AppTheme" parent="Theme.Material3.Dark.NoActionBar">
<item name="colorPrimary">@color/md_theme_dark_primary</item>
<item name="colorOnPrimary">@color/md_theme_dark_onPrimary</item>
<item name="colorPrimaryContainer">@color/md_theme_dark_primaryContainer</item>
<item name="colorOnPrimaryContainer">@color/md_theme_dark_onPrimaryContainer</item>
<item name="colorError">@color/md_theme_dark_error</item>
<item name="colorOnError">@color/md_theme_dark_onError</item>
<item name="colorErrorContainer">@color/md_theme_dark_errorContainer</item>
<item name="colorOnErrorContainer">@color/md_theme_dark_onErrorContainer</item>
<item name="colorOnBackground">@color/md_theme_dark_onBackground</item>
<item name="colorSurface">@color/md_theme_dark_surface</item>
<item name="colorOnSurface">@color/md_theme_dark_onSurface</item>
……
</style>
</resources>
Colors.xml
Colors.xml
<resources>
<color name="md_theme_light_primary">#6750A4</color>
<color name="md_theme_light_onPrimary">#FFFFFF</color>
<color name="md_theme_light_primaryContainer">#EADDFF</color>
<color name="md_theme_light_onPrimaryContainer">#21005D</color>
<color name="md_theme_light_error">#B3261E</color>
<color name="md_theme_light_onError">#FFFFFF</color>
<color name="md_theme_light_errorContainer">#F9DEDC</color>
<color name="md_theme_light_onErrorContainer">#410E0B</color>
<color name="md_theme_light_surface">#FFFBFE</color>
<color name="md_theme_light_onSurface">#1C1B1F</color>
<color name="md_theme_light_surfaceVariant">#E7E0EC</color>
<color name="md_theme_dark_primary">#D0BCFF</color>
<color name="md_theme_dark_onPrimary">#381E72</color>
<color name="md_theme_dark_primaryContainer">#4F378B</color>
<color name="md_theme_dark_onPrimaryContainer">#EADDFF</color>
<color name="md_theme_dark_secondary">#CCC2DC</color>
<color name="md_theme_dark_onSecondary">#332D41</color>
<color name="md_theme_dark_secondaryContainer">#4A4458</color>
<color name="md_theme_dark_onSurface">#E6E1E5</color>
<color name="md_theme_dark_surfaceVariant">#49454F</color>
</resources>
Weitere Informationen:
Weitere Informationen zu dynamischen Farben, benutzerdefinierten Farben und zum Generieren von Tokens findest du auf der Material 3-Seite Dynamische Farben.
Wenn Sie die grundlegende Farbpalette sowie die Farben und das Design Ihrer App generieren möchten, können Sie den Material Theme Builder verwenden, der über ein Figma-Plug-in oder im Browser verfügbar ist.
Weitere Informationen dazu, wie Sie mit Farbschemata die Barrierefreiheit in Ihrer App verbessern können, finden Sie auf der Material 3-Seite Barrierefreiheit des Farbsystems.
Benutzerdefinierte oder Markenfarben beibehalten
Wenn deine App benutzerdefinierte oder Markenfarben hat, die sich nicht mit den Einstellungen des Nutzers ändern sollen, kannst du sie beim Erstellen deines Farbschemas einzeln hinzufügen. Beispiel:
Themes.xml
<resources>
<style name="AppTheme" parent="Theme.Material3.Light.NoActionBar">
...
<item name="home_lamp">@color/home_yellow</item>
...
</style>
</resources>
Colors.xml
<resources>
<color name="home_yellow">#E8D655</color>
</resources>
Alternativ können Sie mit dem Material Theme Builder zusätzliche Farben importieren, um Ihr Farbschema zu erweitern und so ein einheitliches Farbsystem zu erstellen.
Verwenden Sie bei dieser Option HarmonizedColors, um den Farbton benutzerdefinierter Farben zu ändern. In Kombination mit nutzergenerierten Farben wird so eine visuelle Balance und ein barrierefreier Kontrast erreicht. Sie tritt zur Laufzeit mit applyToContextIfAvailable() auf.
Abbildung 6. Beispiel für die Harmonisierung benutzerdefinierter Farben
Weitere Informationen finden Sie im Material 3-Leitfaden zum Harmonisieren benutzerdefinierter Farben.
Dynamische Farben auf adaptive Symbole und Widgets anwenden
Zusätzlich zur Aktivierung von dynamischen Farbschemata in Ihrer App können Sie ab Android 12 auch dynamische Farbschemata für Widgets und ab Android 13 für adaptive Symbole unterstützen.
Alle Inhalte und Codebeispiele auf dieser Seite unterliegen den Lizenzen wie im Abschnitt Inhaltslizenz beschrieben. Java und OpenJDK sind Marken oder eingetragene Marken von Oracle und/oder seinen Tochtergesellschaften.
Zuletzt aktualisiert: 2026-10-24 (UTC).
[[["Leicht verständlich","easyToUnderstand","thumb-up"],["Mein Problem wurde gelöst","solvedMyProblem","thumb-up"],["Sonstiges","otherUp","thumb-up"]],[["Benötigte Informationen nicht gefunden","missingTheInformationINeed","thumb-down"],["Zu umständlich/zu viele Schritte","tooComplicatedTooManySteps","thumb-down"],["Nicht mehr aktuell","outOfDate","thumb-down"],["Problem mit der Übersetzung","translationIssue","thumb-down"],["Problem mit Beispielen/Code","samplesCodeIssue","thumb-down"],["Sonstiges","otherDown","thumb-down"]],["Zuletzt aktualisiert: 2026-10-24 (UTC)."],[],[]]