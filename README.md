# MDC VPN

Kurumsal, sade ve göz yormayan bir koyu temaya sahip Android VPN istemcisi.
Gerçek tünel **OpenVPN 3** çekirdeği (`ics-openvpn` / OVPN3) ile Android'in
yerleşik `VpnService` altyapısı üzerinde kurulur.

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
- Gömülü `assets/default.ovpn` profili
- Dosya seçiciden kendi `.ovpn` dosyasını içe aktarma (uygulama özel alanına kopyalanır)
- Süreç öldürülüp yeniden açıldığında açık tünelin durumunu geri yükleme
- Bağlantı 45 saniyede kurulamazsa otomatik vazgeçme

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

`.github/workflows/release.yml` her `main` push'unda çalışır:

1. `versionCode = 1000 + run_number`, `versionName = 1.0.<run_number>`
2. Release APK derlenir ve `MDC-VPN.apk` olarak yeniden adlandırılır
3. `v1.0.<run_number>` etiketiyle GitHub Release oluşturulur, APK eklenir
4. APK ayrıca iş akışı artefaktı olarak saklanır

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
- Bu sürüm **sertifika tabanlı** profiller içindir. `auth-user-pass` (kullanıcı
  adı/parola) akışı OVPN3 istemcisinde ek kimlik sağlayıcı gerektirdiği için
  kapsam dışıdır.
- `assets/default.ovpn` bir şablondur; gerçek sunucu bilgileri ve sertifikalarla
  değiştirilmeden bağlantı kurulmaz.
- `foregroundServiceType="specialUse"` Google Play'de gerekçe beyanı gerektirir.
