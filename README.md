# Minimal GPS Logger

Minimalna aplikacja Android napisana w Javie. Łączy się z zewnętrznym odbiornikiem GPS przez Bluetooth Classic SPP/RFCOMM i zapisuje surowy strumień NMEA do pliku lokalnego.

Projekt celowo **nie generuje GPX** i nie używa bazy danych. Format zapisu to tylko natywne dane z GPS-a: `.nmea`.

## Najważniejsze decyzje

- Język: Java 17.
- UI: natywne `Activity`, bez AppCompat, bez AndroidX, bez Compose.
- Bluetooth: Classic RFCOMM/SPP.
- UUID SPP: `00001101-0000-1000-8000-00805F9B34FB`.
- Praca w tle: `ForegroundService` z typem `connectedDevice`.
- Storage: app-specific external storage, katalog `tracks`.
- Format pliku: raw NMEA, jeden wiersz = jedno zdanie NMEA.
- Parser live: tylko do statystyk na ekranie.
- JSpecify: `org.jspecify:jspecify:1.0.0` jako `compileOnly`.

## Co aplikacja robi

1. Pokazuje listę sparowanych urządzeń Bluetooth.
2. Pozwala wybrać odbiornik GPS.
3. Startuje foreground service.
4. Łączy się z urządzeniem przez `BluetoothSocket`.
5. Czyta `InputStream` w buforze 4096 bajtów.
6. Składa linie NMEA po `CR/LF`.
7. Zapisuje każdą linię do pliku `.nmea` przez `BufferedOutputStream`.
8. Parsuje wybrane zdania NMEA tylko po to, aby pokazać statystyki:
   - RMC,
   - GGA,
   - GSA,
   - GSV,
   - VTG.
9. Odświeża UI raz na sekundę.
10. Wykonuje flush pliku co 5 sekund.
11. Rotuje plik po 100 MB.
12. Próbuje reconnect z backoffem po błędzie I/O.

## Czego aplikacja celowo nie robi

- Nie używa `LocationManager` jako źródła lokalizacji.
- Nie używa `OnNmeaMessageListener`, bo to jest API dla GNSS telefonu, nie dla zewnętrznego GPS Bluetooth.
- Nie używa GPX.
- Nie używa SQLite/Room do próbek.
- Nie robi aktywnego skanowania Bluetooth.
- Nie wymaga `ACCESS_FINE_LOCATION`.
- Nie aktualizuje GUI dla każdej próbki.
- Nie robi `fsync` po każdej linii.
- Nie używa regexów ani `Scanner` w gorącej ścieżce odczytu.

## Pliki wyjściowe

Na Androidzie pliki powstają w katalogu aplikacji, zwykle w lokalizacji podobnej do:

```text
Android/data/com.github.mikoreg.gpslogger/files/tracks/gps_YYYYMMDD_HHMMSS_part001.nmea
```

Przykład nazwy:

```text
gps_20260425_173012_part001.nmea
```

## Budowanie

Wymagania:

- Android Studio albo Gradle z Android Gradle Plugin.
- JDK 17.
- Android SDK z `compileSdk 35`.

Budowanie z linii poleceń, jeżeli masz Gradle:

```bash
gradle assembleDebug
```

Albo otwórz katalog projektu w Android Studio i uruchom konfigurację `app`.

## Testowanie z prawdziwym GPS

1. Sparuj odbiornik GPS z telefonem w ustawieniach Androida.
2. Uruchom aplikację.
3. Nadaj uprawnienie Bluetooth.
4. Wybierz sparowane urządzenie z listy.
5. Kliknij `Start`.
6. Sprawdź statystyki:
   - `Status: LOGGING`,
   - rosnący licznik `sentences total`,
   - rosnący rozmiar pliku,
   - po uzyskaniu fixu: współrzędne, speed, satellites, HDOP.

## Najważniejsze klasy

```text
MainActivity.java
    Minimalny GUI: wybór urządzenia, Start/Stop, statystyki.

GpsLoggerService.java
    ForegroundService utrzymujący proces logowania.

NmeaLoggerEngine.java
    Główna pętla Bluetooth connect/read/reconnect.

NmeaFileLogger.java
    Buforowany zapis raw NMEA i rotacja pliku.

NmeaParser.java
    Lekki parser statystyk bez regexów.

NmeaChecksum.java
    Walidacja checksum NMEA.

NmeaStats.java
    Niemutowalny snapshot statystyk dla UI.

MutableNmeaStats.java
    Wewnętrzny, synchronizowany stan statystyk.
```

## Uwagi implementacyjne

Gorąca ścieżka danych wygląda tak:

```text
BluetoothSocket.getInputStream()
    -> read(byte[4096])
    -> składanie linii do byte[1024]
    -> BufferedOutputStream.write(...)
    -> lekki parser statystyk
```

Aplikacja zapisuje linię do pliku **przed** parsowaniem. Dzięki temu błędne albo nierozpoznane zdania NMEA nadal zostają w pliku diagnostycznym.

## Możliwe następne kroki

- Dodanie przycisku `Udostępnij ostatni plik` przez `FileProvider`.
- Dodanie ustawienia częstotliwości flush: 1 s / 5 s / 10 s.
- Dodanie importu pliku NMEA do podglądu offline.
- Dodanie opcjonalnego transportu BLE dla konkretnych modeli GPS.
- Dodanie eksportu GPX jako osobnej funkcji offline, bez zmiany formatu podstawowego.
