# MDC VPN

Kurumsal, sade ve göz yormayan bir koyu temaya sahip VPN istemcisi.
Aynı depoda iki sürüm bulunur: **Android** (Kotlin + `VpnService`) ve
**Windows x64** (Java/Swing + OpenVPN / WireGuard).

Gerçek tünel Android'de **OpenVPN 3** çekirdeği (`ics-openvpn` / OVPN3) ile
Android'in yerleşik `VpnService` altyapısı üzerinde, Windows'ta ise paketlenen
OpenVPN Community istemcisi veya kurulu WireGuard ile kurulur.

| | |
|---|---|
| Paket adı | `com.mdc.vpn` |
| Dil | Kotlin |
| Arayüz | XML + Material Design 3 |
| minSdk / targetSdk / compileSdk | 24 / 34 / 34 |
| JDK | 17 |
| Gradle / AGP / Kotlin | 8.7 / 8.5.2 / 1.9.24 |

## Tasarım

Klişe neon/gradyan efektler yerine kurumsal bir palet kullanılır:

| Rol | Renk |
|---|---|
| Arka plan (mat antrasit) | `#121417` |
| Kart yüzeyi (koyu gri) | `#1E2229` |
| Yükseltilmiş yüzey | `#262C34` |
| Vurgu (zümrüt yeşili) | `#10B981` |
| Ana metin | `#F2F5F8` |
| İkincil metin | `#9BA5B4` |

Tek ekranda: durum halkası, tek tuşla bağlan/kes, oturum süresi, anlık
indirme/yükleme hızı ve `.ovpn` profil seçimi.

## Özellikler

- Tek dokunuşla **Bağlan / Bağlantıyı Kes**
- Anlık durum: *Bağlı değil · Bağlanıyor · Güvende · Kesiliyor*
- Oturum süresi sayacı, indirme/yükleme hızı göstergesi
- Kalıcı (foreground) bildirim: süre kronometresi + **Bağlantıyı Kes** aksiyonu
- OpenVPN ve WireGuard protokolleri
- **DPI atlatma (Windows)**: zapret (`winws` / `winws2`) + hazır Türkiye stratejileri
- **DPI atlatma (Android 10+)**: `VpnService` + hev-socks5-tunnel + ByeDPI (`ciadpi`)
- Dosya seçiciden kendi `.ovpn` / `.conf` dosyasını içe aktarma (uygulama özel alanına kopyalanır)
- Eski sunucu uyumluluğu (BF-CBC, `ns-cert-type`, `tls-remote`, LZO, TLS 1.0)
- `auth-user-pass` profillerinde kullanıcı adı/parola sorma
- Eksik veya şablon profilde bağlanmayı denemek yerine nedeni açıklama
- Süreç öldürülüp yeniden açıldığında açık tünelin durumunu geri yükleme
- Bağlantı 45 saniyede kurulamazsa otomatik vazgeçme ve gerçek hatayı gösterme

## Mimari

```
MainActivity ──► VpnConnectionManager ──► OpenVPNService (ayrı süreç: :openvpn)
   │                    │                        │
   │                    │                        └─ OVPN3 / libovpn3.so
   │                    └─ AIDL (IVPNService) durum geri çağrıları
   └─ StatusRingView / view binding
```

- `VpnConnectionManager` uygulama sürecinde tekil (singleton) olarak çalışır;
  `android.net.VpnService.prepare()` izni aktivitede alınır, servis
  `OpenVPNService.startService(...)` ile başlatılır.
- Tünelin gerçekten ayakta olup olmadığı `ConnectivityManager` üzerinden
  `TRANSPORT_VPN` taşıyıcısı ile doğrulanır; bu, süreç yeniden başladığında
  durumun doğru gösterilmesini sağlar.
- Hız göstergesi `TrafficStats` toplam trafik farkından hesaplanır (VPN açıkken
  tüm trafik tun arayüzünden geçer).
- Bildirim sınıfı (`MdcVpnNotification`) kütüphane tarafından yansıma
  (`Class.forName` + `(Service, NotificationManager)` kurucusu) ile oluşturulduğu
  için ProGuard kurallarında korunur.

### Neden `ics-openvpn` çekirdeği?

`io.github.tim06:openvpn:1.1.3` artefaktı, `ics-openvpn` projesinin OVPN3
istemcisini (`libovpn3.so`, dört ABI) Android `VpnService` ile birleştirir;
`assets` içindeki `.ovpn` metnini doğrudan çekirdeğe verir. Böylece OpenVPN
protokolünü yeniden yazmadan gerçek bir tünel kurulur.

### DPI atlatma (ByeDPI)

Android 10+ cihazlarda üçüncü protokol olarak **ByeDPI** seçilebilir. Bu bir
VPN değildir: trafiği şifrelemez, DPI filtrelerini TCP parçalama/desync ile
atlatır.

- `ciadpi` ([hufrea/byedpi](https://github.com/hufrea/byedpi), MIT) her ABI
  için `jniLibs/<abi>/libciadpi.so` olarak paketlenir ve `nativeLibraryDir`
  üzerinden yerel SOCKS5 proxy (127.0.0.1:1080) olarak çalıştırılır.
- `ByeDpiVpnService`, `VpnService.Builder` ile tun açar ve uygulamayı tun
  dışında bırakır.
- [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) (MIT)
  tun dosya tanımlayıcısını SOCKS5'e bağlar (`libs/hev-socks5-tunnel.aar`).
- Hazır stratejiler: Dengeli (`split 1 + disorder`), Güçlü
  (`split 1+s + disorder 3+s`), Sahte paket (`--fake -1 --ttl 8`), TLS kayıt
  bölme (`--tlsrec 1+s`) ve özel `ciadpi` argümanları.
- ByeDPI AAR'ı minSdk 29 bildirdiği için seçenek yalnızca Android 10+ üzerinde
  çalışır; eski cihazlarda OpenVPN ve WireGuard kullanılmaya devam eder.

## Windows x64 sürümü

`desktop/` modülü, Android modülünden bağımsız bir Java/Swing uygulamasıdır.
Harici kütüphane kullanmaz; `jpackage` ile `MDC-VPN.exe` üreten portatif bir
klasöre dönüştürülür.

- Arayüz aynı kurumsal koyu temayı kullanır: durum halkası, tek tuşla
  bağlan/kes, süre ve indirme/yükleme hızı, profil ve protokol seçimi, günlük.
- **OpenVPN**: paket içindeki `openvpn\openvpn.exe` gizli pencerede çalışır;
  durum ve trafik yerel yönetim arayüzünden okunur. Tünel için gereken
  `wintun.dll` sürücüsü de pakete dâhil edilir.
- **WireGuard**: cihazda kurulu [WireGuard for Windows](https://www.wireguard.com/install/)
  istemcisini kullanır.
- **DPI atlatma (zapret)**: paketteki `zapret\winws.exe` (Zapret 1) veya
  `zapret\winws2.exe` (Zapret 2) seçilen Türkiye stratejisiyle gizli süreç
  olarak çalıştırılır. Stratejiler
  [alimali54/zapret-win-turkey](https://github.com/alimali54/zapret-win-turkey)
  deposundaki hazır profillerden alınmıştır (Türk Telekom, Superonline,
  Vodafone, Turkcell, Türksat Kablonet). Zapret, VPN'den bağımsız olarak da
  açılabilir; WinDivert sürücüsü için yönetici izni gerekir.
- Tünel açmak için **yönetici izni** gerekir; uygulama gerekirse UAC ile kendini
  yeniden başlatmayı önerir.
- Profiller `%APPDATA%\MDC VPN\profiles` altında, günlükler `logs` altında tutulur.
- `auth-user-pass` profilleri için kullanıcı adı/parola sorulur ve yalnızca
  bağlantı süresince geçici bir dosyada tutulur.

```powershell
# Yalnızca JAR (JDK 17 yeterli)
.\gradlew.bat -p desktop jar -PappVersion=1.0.9

# Windows'ta uygulama klasörü
# (JAR -> desktop\build\libs\mdc-vpn-desktop-1.0.9.jar)
jpackage --type app-image --name MDC-VPN --app-version 1.0.9 `
  --input desktop\build\libs --main-jar mdc-vpn-desktop-1.0.9.jar `
  --main-class com.mdc.vpn.desktop.Main --icon desktop\icon.ico --dest dist\windows
```

## Derleme

```bash
# Gerekenler: JDK 17, Android SDK 34 (platform + build-tools)
export ANDROID_HOME=$HOME/Android/Sdk     # veya local.properties içine sdk.dir yazın

./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease      # app/build/outputs/apk/release/app-release.apk
```

Sürüm bilgisi CI'dan enjekte edilir:

```bash
./gradlew assembleRelease -PversionCode=1001 -PversionName=1.0.1
```

## İmzalama

İmzalı sürüm için bir keystore üretin ve değerleri ortam değişkeni ya da
`~/.gradle/gradle.properties` üzerinden verin (dosyaya **yazmayın**):

```bash
keytool -genkeypair -v -keystore release.keystore -alias mdcvpn \
  -keyalg RSA -keysize 4096 -validity 10000
base64 -w 0 release.keystore > release.keystore.b64
```

Kullanılan değişkenler: `MDC_KEYSTORE_FILE`, `MDC_KEYSTORE_PASSWORD`,
`MDC_KEY_ALIAS`, `MDC_KEY_PASSWORD`.

Keystore verilmezse sürüm APK'sı debug anahtarıyla imzalanır; böylece çatallanan
depolarda da CI kırılmaz (APK yine kurulabilir, ancak Play Store'a yüklenemez).

## GitHub Actions (otomatik sürüm)

`.github/workflows/release.yml` her `main` push'unda üç iş çalıştırır:

1. `versionCode = 1000 + run_number`, `versionName = 1.0.<run_number>`
2. **android**: Release APK derlenir, `MDC-VPN.apk` olarak yeniden adlandırılır
3. **windows**: `desktop` JAR'ı derlenir, OpenVPN Community MSI'ından çıkarılan
   `openvpn.exe`, `wintun.dll` ve `desktop/zapret` (winws + Lua + WinDivert)
   ile birlikte `jpackage` uygulama klasörü üretilir ve
   `MDC-VPN-Windows-x64.zip` olarak paketlenir
4. **release**: `v1.0.<run_number>` etiketiyle GitHub Release oluşturulur ve
   iki dosya da eklenir: `MDC-VPN.apk`, `MDC-VPN-Windows-x64.zip`

### Depo ayarları

1. **Settings → Actions → General → Workflow permissions**
   → *Read and write permissions* seçin ve *Allow GitHub Actions to create and
   approve pull requests* kutusunu işaretleyin (veya iş akışındaki
   `permissions: contents: write` bloğuna güvenin; ikisi birlikte çalışır).
2. **Settings → Secrets and variables → Actions** (opsiyonel, imzalı APK için):
   - `KEYSTORE_BASE64` – `base64 -w 0 release.keystore` çıktısı
   - `KEYSTORE_PASSWORD`
   - `KEY_ALIAS`
   - `KEY_PASSWORD`

`GITHUB_TOKEN` otomatik sağlanır; ek bir token gerekmez.

## Güvenlik notları

- Yapılandırmalar uygulama özel alanında tutulur, `allowBackup=false`.
- Özel anahtar içeren `.ovpn` dosyaları cihaz yedeğine dâhil edilmez.
- `auth-user-pass` profillerinde girilen kullanıcı adı/parola uygulama özel
  alanındaki bir dosyaya yazılır ve OVPN3'e bu dosya üzerinden verilir; dosya
  cihaz yedeğine dâhil edilmez. Windows sürümünde dosya bağlantı kapanınca silinir.
- `assets/default.ovpn` bir şablondur; gerçek sunucu bilgileri ve sertifikalarla
  değiştirilmeden bağlantı kurulmaz.
- `foregroundServiceType="specialUse"` Google Play'de gerekçe beyanı gerektirir.
