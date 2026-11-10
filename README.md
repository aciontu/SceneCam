# ScenaCam

Aplicație de cameră pentru Android (gândită pentru Samsung Galaxy A56) care recunoaște scena cu AI direct pe telefon și își reglează singură expunerea.

## Ce face

- **Recunoaște scena de ~2 ori pe secundă, fără internet**: obiecte și tip de scenă (ML Kit Image Labeling), fețe (ML Kit Face Detection), histogramă de lumină, luminozitatea reală a scenei (dedusă din ISO și timpul alese de aparat) și cât de stabil ții telefonul (giroscop).
- **Scene**: Noapte, Lumină slabă, Portret, Portret de noapte, Contralumină, Peisaj, Apus/răsărit, Mâncare, Flori și plante, Zăpadă, Animale, Standard.
- **Stiluri de culoare** (butonul „Stil"): Original (neprelucrat), Viu (culori saturate, contrast mai mare), Luminos (imagine mai deschisă și mai blândă). În Viu și Luminos, culorile se reglează și după scenă. Efectul se vede în poza salvată, nu în previzualizare.
- **Mod Noapte din mai multe cadre** (butonul „Noapte+"): în scenele Noapte, Lumină slabă și Portret de noapte face 4–8 cadre la rând, le aliniază și le combină. Zgomotul scade, iar umbrele sunt deschise. Cât timp cercul butonului e galben, ține telefonul nemișcat; al doilea sunet de declanșator anunță că îl poți mișca. Prelucrarea mai durează câteva secunde după aceea.
- **Bokeh** (butonul de lângă „Noapte+"): folosește procesarea de estompare a fundalului a telefonului, pentru portrete. Efectul se vede și în previzualizare. În acest mod recunoașterea scenei și focalizarea prin atingere sunt oprite. Butonul apare doar dacă telefonul oferă Bokeh aplicațiilor terțe.
- **Diagnostic**: butonul „Info" arată ce oferă telefonul aplicațiilor de cameră (senzor manual, RAW, extensiile producătorului).
- **Reglaje automate pe fiecare scenă**:
  - *Noapte / lumină slabă*: dacă ții telefonul stabil, alege timp de expunere mai lung (până la 1/8 s din mână, 1/2 s pe trepied) și ISO mai mic, deci mai puțin zgomot. Dacă miști telefonul, scurtează timpul ca să nu iasă poza mișcată.
  - *Portret*: măsoară lumina și focalizează pe față, +0,3 EV, timp de expunere scurt ca să nu iasă persoana mișcată.
  - *Contralumină*: expune pentru față, nu pentru fundalul luminos.
  - *Peisaj*: −0,3 EV ca să nu ardă cerul, ISO minim.
  - *Apus*: −0,7 EV și balans de alb „zi", ca să păstreze culorile calde.
- **Atinge ecranul** pentru focalizare și măsurare într-un punct.
- **Zoom cu două degete** pe previzualizare; atinge indicatorul (ex. `2.0×`) ca să revii la 1×. Peste 1× zoomul este digital, deci calitatea scade treptat.
- **Butonul din dreapta** comută între „AI automat" și o scenă aleasă de tine.
- Pozele se salvează în `DCIM/ScenaCam`, la rezoluția maximă 4:3 a camerei principale.

Sus pe ecran vezi mereu scena detectată, setările cu care se va face poza (`ISO · timp · EV`, cu `manual` când aplicația preia controlul senzorului) și ce a recunoscut AI-ul.

## Limite, spuse direct

- Aplicația folosește camera principală din spate, fără filmare.
- Galaxy A56 nu oferă aplicațiilor terțe control manual ISO/timp și nici RAW (verificat cu ecranul Info). Pe acest telefon aplicația lucrează prin compensare de expunere, măsurare pe față, balans de alb, cadență redusă și combinarea mai multor cadre. Pe telefoane care oferă senzor manual, alege și ISO/timp.
- Nu are acces la procesarea Samsung pentru HDR și Noapte (telefonul expune doar Bokeh și Face Retouch). Modul Noapte propriu combină fotografii deja prelucrate de telefon, nu date brute de senzor, deci în întuneric puternic camera Samsung poate da rezultate mai bune.
- Codul a fost verificat la compilare față de API-ul Android și logica de decizie are teste, dar aplicația nu a fost încă rulată pe un telefon real.

## Cum obții APK-ul

### Varianta 1: GitHub (merge și de pe telefon)

1. Creează un repository pe github.com și urcă acolo conținutul acestui folder (ramura `main`).
2. În tabul **Actions** rulează automat „Build APK" (durează câteva minute).
3. În **Releases** apare `ScenaCam.apk`. Descarcă-l pe telefon și instalează-l (Android cere permisiunea „Instalare aplicații necunoscute" pentru browser).

### Varianta 2: Android Studio

1. Deschide folderul în Android Studio și așteaptă sincronizarea Gradle.
2. Conectează telefonul prin USB cu „Depanare USB" activă și apasă **Run**.

Din linie de comandă: `./gradlew assembleDebug`, iar APK-ul apare în `app/build/outputs/apk/debug/`.

## Structura codului

| Fișier | Rol |
| --- | --- |
| `SceneEngine.kt` | Decide scena și setările. Aici modifici pragurile și reglajele. |
| `NightStack.kt` | Alinierea și combinarea cadrelor pentru modul Noapte. |
| `ColorTuning.kt` | Stilurile și reglajele de culoare pe scenă. |
| `SceneAnalyzer.kt` | Rulează modelele AI pe cadrele din previzualizare. |
| `CameraController.kt` | Camera2: previzualizare, aplicarea setărilor, captură, salvare. |
| `SteadinessMonitor.kt` | Stabilitatea telefonului, din giroscop. |
| `MainActivity.kt`, `Views.kt` | Interfața. |
