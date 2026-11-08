# Doppia 1.12 (note vocali, avvisi batteria e memoria, misuratore Kelvin, live view, REC, esposizione, WB, monitor, segni e take per Premiere, Sony PTP 3)

Due camere Sony (FX30 e A6700) una accanto all'altra sul Xiaomi Pad 5, via Wi-Fi.

Questa versione serve a capire **cosa risponde ogni camera**: si collega con il protocollo Sony PTP/IP
(porta 15740), fa l'handshake Sony, chiede il live view e scrive tutto nel Log.
Non e' ancora provata su una camera vera: il log serve proprio a correggere il tiro.

## Come provare
1. Accendi l'hotspot del tablet (o usa un router) e collega le due camere a quella rete dal menu Rete / Wi-Fi.
2. Sulle camere attiva il controllo da smartphone (i nomi dei menu cambiano da modello a modello).
3. Apri Doppia, vai in **Impostazioni > Camere**: scrivi il nome e l'indirizzo IP di ogni camera.
4. Tocca **Connetti** al centro di ogni riquadro. Il **Log** si apre da Impostazioni.
5. Tocca un riquadro per metterlo a tutto schermo, di nuovo per tornare ai due.

Il numero accanto al nome e' il tempo di un fotogramma (colore dal freddo al caldo).

## Compilare
Android Studio (JDK 17) oppure GitHub: `.github/workflows/build.yml` produce l'APK come artifact.

## Novita' 0.3
- **REC**: pulsante rotondo a destra su ogni riquadro, e **REC A+B** in alto per avviare/fermare entrambe. Mentre registra: cornice rossa e pillola REC con il tempo. Lo stato viene letto dalla camera, non ipotizzato.
- **Barra esposizione** in basso (come il monitor Sony): SHUTTER, IRIS, ISO. Tocca un valore: compare la fila dei valori ammessi dalla camera, tocca quello voluto. Grigio = la camera non lo lascia cambiare in questa modalita'.
- Nel Log: tabella proprieta', esito dei comandi e, dopo ogni REC, quali proprieta' sono cambiate (serve a verificare il codice dello stato REC 0xD21D).

## Novita' 0.4
- Barra monitor in basso come Sony: REC, SHUTTER, IRIS, ISO ed esposimetro sulla stessa riga.
- **Impostazioni**: zebra (livello), focus peaking (colore), griglia dei terzi, istogramma, esposimetro; per ogni camera live: modo esposizione, compensazione EV, misurazione, bilanciamento bianco.
- REC verificato: prova piu' metodi finche' lo stato REC letto dalla camera cambia davvero (bordo rosso + tempo).
- Tabella proprieta' letta anche con stringhe e array (prima si fermava e il diaframma spariva); diaframma anche via 0x9203.
- "Copia log e proprieta' delle camere" in Impostazioni: copia tutto negli appunti.

## Novita' 0.5
- Barra in alto ridotta: titolo, indicatore camere a sinistra; REC A+B e Impostazioni a destra.
- Tolti **Cerca camere** (non funzionava) e la riga con gli IP in basso.
- **Impostazioni > Camere**: nome e indirizzo IP di ogni camera, tasto Scollega.
- **Connetti / Riprova / Annulla** al centro del riquadro, sopra il messaggio di stato; sparisce con la camera live.
- **Log** spostato in Impostazioni (Diagnostica > Apri il log).

## Novita' 0.6
- **Collegamento automatico** all'avvio per le camere con IP salvato (si spegne in Impostazioni > Camere).
- **REC A+B** grigio finche' nessuna camera e' live.
- **Puntino di stato** accanto al nome nel riquadro: grigio spenta, giallo in collegamento, verde live, rosso errore.

## Novita' 0.7
- **Sync**: tasto in alto, giallo quando e' attivo. Con Sync accesa, ogni modifica di otturatore, diaframma, ISO, modo esposizione, compensazione EV, misurazione e bilanciamento bianco su una camera viene applicata anche all'altra (se e' live e accetta quel valore; altrimenti lo dice nel log).
- **⚑ Segna**: segna un momento con l'ora e il tempo di REC di ogni camera che registra. L'elenco e' in Impostazioni (Copia, Condividi, Svuota) e resta salvato.

## Novita' 0.8
- **Aggiornamenti OTA**: l'app cerca l'ultima versione su GitHub (Releases) e la installa sopra quella vecchia. Tasto giallo **⬆ Aggiorna** in alto quando ce n'e' una nuova; ricerca manuale e interruttore in Impostazioni > Aggiornamenti.
- Ogni compilazione su GitHub pubblica una Release con l'APK (`Doppia.apk`) e queste note.
- L'aggiornamento non parte se una camera sta registrando.

## Novita' 1.12
- **Nota vocale**: **tieni premuto ⚑ Nota** (in Setup e in Ripresa), parla e rilascia: il testo detto diventa la nota del segno, in italiano. Un tocco breve apre ancora la finestra con frasi rapide e testo. Il segno prende il momento in cui hai iniziato a tenere premuto. La prima volta Android chiede il permesso del microfono; poi tieni premuto di nuovo. Il testo finisce nell'elenco, nella scheda sessione e nell'esportazione per Premiere come le altre note.
- **Avvisi batteria e memoria**: una camera sotto il 15% di batteria o sotto 10 minuti di spazio fa comparire l'avviso sul riquadro con vibrazione e suono, anche in Ripresa. Avviso una volta sola, si riarma sopra il 20% e sopra 15 minuti. Si spegne in Impostazioni > Take e segni.
- Aggiunto il permesso Microfono (solo per le note vocali).
- **Beep disattivabili**: Impostazioni > Take e segni > **Beep degli avvisi**. Spento, gli avvisi restano con vibrazione e scritta sul riquadro.
- **In REC niente suoni**: se una camera registra, gli avvisi di batteria e memoria non suonano e non vibrano (rientrerebbero nel microfono); la percentuale della batteria o il tempo di memoria **lampeggia in rosso** sul riquadro. Sotto il 15% o 10 minuti il valore è rosso fisso anche fuori REC.

## Novita' 1.10
- **Misuratore Kelvin**: tasto **🌡 Kelvin** in alto (in Setup). Si apre la camera posteriore del tablet: inquadra una superficie bianca o grigia neutra, illuminata dalla luce da misurare. Compare il valore in Kelvin con la scala caldo/freddo.
- **Imposta camere**: porta il valore in Doppia, che mette il bilanciamento del bianco in temperatura colore e i Kelvin misurati su ogni camera live (al valore piu' vicino tra quelli ammessi dalla camera). Il risultato e' scritto nel Log.
- Se il tablet non espone le matrici colore, la misura e' una stima approssimata (valore con ≈ davanti).
- Aggiunto il permesso Camera (solo per il misuratore).
- **Impostazioni sul telefono in orizzontale**: i tasti Esporta per Premiere, Copia elenco e Azzera sessione vanno a capo se non c'e' spazio, invece di schiacciarsi (e lasciare un vuoto nella finestra).

## Novita' 1.8
- **Verticale e orizzontale**: l'app ruota con il tablet. In orizzontale le camere sono affiancate, in verticale una sopra l'altra (la barra in alto scorre di lato se i tasti non stanno). Il tocco su un riquadro lo ingrandisce a tutto schermo in entrambe le posizioni.
- **Batteria e memoria corrette** (verificate sul log di FX30 e A6700): la batteria in percentuale è la proprietà 0xD218, il tempo residuo è 0xD24A in secondi. Prima l'app leggeva il livello a gradini (100% / 66%) e uno slot spento ("0m").

## Novita' 1.6
- **Modalità Ripresa**: tasto **▶ Ripresa / ⚙ Setup** in alto (si ricorda anche alla riapertura). In Ripresa restano il live, il tally rosso, il tempo di REC, batteria e memoria, gli avvisi e, in basso, tre pulsanti grandi per i segni (**⚑ Buona**, **⚑ Scarto**, **⚑ Nota**). Spariscono zebra, peaking, istogramma, esposimetro, griglia, barra otturatore/iris/ISO/WB, screenshot, sovrapposizione, Sync, Impostazioni. Il REC resta (più grande). Le guide inquadratura restano disegnate (bloccate).
- **Stop REC protetto**: per fermare si **tiene premuto** (0,8 s, con anello che si riempie) il pulsante REC del riquadro o **■ STOP A+B**. Un tocco breve non fa nulla e lo ricorda. L'avvio resta a un tocco.
- **Riconnessione automatica**: se una camera che era live perde il Wi-Fi, l'app riprova da sola (ogni 2-4 s, poi ogni 12 s). Il riquadro resta con l'ultimo fotogramma scuro e **mantiene lo stato REC** (il take non si chiude), con avviso, vibrazione e suono se stava registrando. Quando torna, il buco resta **segnato nel take**: elenco, `.json` (connectionLost), `.csv` (colonna "connessione persa"), marker "Connessione persa" nell'XML. Sul riquadro c'è **Smetti di riprovare**.
- **Avviso REC non allineato**: se due camere sono live e una registra e l'altra no per più di 2,5 s, il riquadro che non registra lampeggia arancione con "NON IN REC". Si spegne in Impostazioni > Take e segni.
- **Scheda sessione**: Impostazioni > Take e segni > **Scheda sessione**. File `.txt` da condividere con: data e orario, per camera take, minuti girati, il take più lungo, segni, collegamenti persi, ultima batteria e memoria letta, poi elenco take (con nomi) e segni.
- **Nomi dei take**: tasto **🎬 Scena** in alto. Scrivi la scena (con le ultime usate a un tocco) e l'inquadratura di ogni camera (Totale, Campo medio, Primo piano, Dettaglio, Cutaway, Making of o testo libero). Il nome del take è "Scena - inquadratura" e compare nell'elenco, nel nome della sequenza nell'XML, nel `.json` (name) e nel `.csv` (colonna "nome"). Si vede anche sul riquadro.
- **Avvisi di REC fermato e caldo**: se un REC si ferma senza che Doppia l'abbia chiesto (caldo, scheda piena, batteria o tasto sulla camera) compare un avviso con vibrazione e suono, e il log scrive quali proprietà della camera sono cambiate. **Il codice Sony della temperatura non è noto**: copia il log dopo uno stop per caldo e lo inserisco (`SonyProps.TEMP_WARN`). Il log scrive anche le proprietà sconosciute che cambiano.
- **Bilanciamento del bianco**: chip **WB** nella barra (AWB, DAY, FLUO, TUNG, CLOUD, SHADE, K, C1-C3) e, con WB in temperatura colore, chip **KELVIN** con i valori ammessi dalla camera. Con Sync si applicano anche all'altra camera. I codici oltre 0x8000 e il codice 0xD20F del Kelvin sono da verificare: all'avvio il log scrive "WB ammessi (esadecimale)". Se vedi codici esadecimali al posto dei nomi, copia il log e correggo.
- Le proprietà Sony a intervallo (min, max, passo) ora si leggono come elenco di valori.

## Novita' 1.5
- **Guide spostabili**: con le guide accese compare sotto la batteria una barra con il lucchetto. Le guide partono **bloccate**. Tocca 🔓 per sbloccarle: la guida scelta (con ↔ o ↕ nel nome) si trascina con un dito. Quelle verticali (9:16, 4:5, 1:1) si spostano a destra e sinistra, la 2.39:1 in alto e in basso.
- **Più guide accese**: tocca il nome di una guida per sceglierla, poi trascinala.
- **Aggancio**: vicino al centro e ai bordi la guida si ferma da sola, con un piccolo tic. Doppio tocco dentro la guida: torna al centro. Durante il trascinamento compare la linea del centro e la posizione in percento.
- **Posizioni rapide**: con le guide sbloccate, tre pulsanti ◀ ● ▶ (▲ ● ▼ per la 2.39:1) portano la guida a sinistra, al centro, a destra.
- **Una posizione per camera**: A e B ricordano ognuna le sue guide. Impostazioni > Monitor: **Copia posizione guide A → B** e **Rimetti tutte le guide al centro**.
- **Valore sempre visibile**: nella barra, per esempio "9:16 · 12% a sinistra".
- **Durante il REC le guide sono sempre bloccate** (lucchetto "REC").
- **Guide nei take**: ogni take salva la posizione delle guide accese all'inizio. Nell'esportazione `.json` c'e' l'elenco "guides" per ogni take, nel `.csv` la colonna "guide". Nell'XML per Premiere non e' inserita.
- La zona sicura 9:16 segue la guida 9:16.

## Novita' 1.4
- **Modalità test (con PIN)**: Impostazioni > Modalità test > Camere simulate. La prima volta scegli un PIN di 4 cifre, poi lo chiede ogni volta. Con la modalità accesa, **Connetti** collega due camere finte (immagine con scritta TEST, zebra e peaking visibili, pallina in movimento) senza usare il Wi-Fi né gli IP.
- Nella camera finta funzionano REC e stop (tally, take, segni, esportazione per Premiere), otturatore, diaframma, ISO, EV, bilanciamento bianco, Sync e screenshot. Cambiando ISO, diaframma, otturatore o EV la luminosità dell'immagine cambia davvero.
- Batteria (parte dal 30%, -1% ogni 6 secondi) e memoria (-12 secondi ogni secondo in REC) scendono in fretta, per vedere i colori di avviso.
- La modalità test si spegne da sola alla riapertura dell'app.

## Novita' 1.3
- **Batteria e memoria sempre visibili**: sotto il nome di ogni camera, "🔋 78%   💾 1h 12m" (tempo di filmato residuo; con due schede, una per slot). Diventa giallo sotto il 25% di batteria o 20 minuti, rosso sotto il 10% o 10 minuti.
- I codici Sony della memoria residua sono da verificare: se vedi "💾 —", copia il log in Impostazioni e correggo i codici.

## Novita' 1.2
- **Guide inquadratura per i social**: Impostazioni > Monitor. Accendi in piu' alla volta **9:16** (Reel, TikTok, Shorts), **4:5** (feed Instagram), **1:1**, **16:9** e **2.39:1**: ogni guida e' un ritaglio centrato sull'immagine, con il suo colore e il suo nome. In piu' la **zona sicura 9:16** (valori approssimati) tiene titoli e testi lontani da dove le app social mettono didascalie e pulsanti. Gli screenshot restano puliti, senza guide.

## Novita' 1.1
- **Screenshot e sovrapposizione**: i due pulsanti sono piu' piccoli e stanno sotto il live view, sopra la barra REC, dal lato esterno: a sinistra nel riquadro di sinistra, a destra in quello di destra.

## Novita' 1.0
- **Segni**: tre pulsanti in alto, **⚑ Buona** (verde), **⚑ Scarto** (rosso) e **⚑ Nota** (giallo). Il segno si aggancia al take in corso di ogni camera che registra, con il tempo dall'inizio del take. **Nota** apre frasi rapide (Audio sporco, Fuori fuoco, Rifare, Taglio qui, Ottima) e un campo di testo, dove si puo' dettare con il microfono della tastiera.
- **Take list**: ogni REC diventa un take per camera (numero, ora di inizio, durata, segni). L'elenco e' in Impostazioni > Take e segni e resta salvato fino ad **Azzera sessione**.
- **Esporta per Premiere** (Impostazioni > Take e segni): condivide tre file.
  - `.xml` (Final Cut Pro 7 / xmeml): in Premiere File > Importa. Una sequenza per take, con i segni come marker di sequenza (nome = tipo e nota). Trascina la clip della camera nella sequenza, partendo dall'inizio: i marker cadono sul punto giusto.
  - `.json`: take e segni con tempi in ms e in fotogrammi, per il plugin UXP.
  - `.csv`: elenco take (separatore ;, si apre in Excel).
- **Fotogrammi dell'XML** (tocca per cambiare, di partenza 25) e **Ritardo rilevamento REC** (di partenza 300 ms): l'app vede il REC fino a 0,6 s dopo la camera, il ritardo viene aggiunto ai tempi dei segni.
- **Tally grande**: con una camera in REC tutto il bordo dell'app diventa rosso, cornice spessa e pillola REC piu' grande (si spegne in Impostazioni).
- **Avviso batteria**: sotto il nome della camera compare il livello quando e' sotto il 25%. Il codice della proprieta' Sony e' da verificare nel log.

## Novita' 0.9
- **Screenshot** per ogni camera: pulsante tondo a destra del riquadro. Salva il fotogramma pulito (senza zebra e strumenti) in Immagini/Doppia e il pulsante diventa **Condividi**, che apre il menu di condivisione (WhatsApp ecc.).
- **Sovrapposizione**: pulsante a livelli sotto lo screenshot. Metti un'immagine (ultimo screenshot della camera oppure dalla Galleria) sopra il live per rifare la stessa inquadratura di un'altra giornata. Cursore di opacita' (50% di partenza); l'immagine resta salvata per ogni camera.

## Da fare dopo la prova
REC/STOP, layout sovrapposto, camera non Sony come flusso di rete, capienza scheda residua.
