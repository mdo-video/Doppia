# Doppia 0.9 (live view, REC, esposizione e strumenti monitor, Sony PTP 3)

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

## Novita' 0.9
- **Screenshot** per ogni camera: pulsante tondo a destra del riquadro. Salva il fotogramma pulito (senza zebra e strumenti) in Immagini/Doppia e il pulsante diventa **Condividi**, che apre il menu di condivisione (WhatsApp ecc.).
- **Sovrapposizione**: pulsante a livelli sotto lo screenshot. Metti un'immagine (ultimo screenshot della camera oppure dalla Galleria) sopra il live per rifare la stessa inquadratura di un'altra giornata. Cursore di opacita' (50% di partenza); l'immagine resta salvata per ogni camera.

## Da fare dopo la prova
REC/STOP, layout sovrapposto, "Segna momento", camera non Sony come flusso di rete.
