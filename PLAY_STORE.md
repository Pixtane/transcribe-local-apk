# Publishing Holos on Google Play (paid, $0.99–$1, buy once)

Everything in the repository is ready: target API 36 (required since 31 Aug 2026), 16 KB
page-size–compatible native libraries, an Android App Bundle build with your private upload
key, store texts in English and Ukrainian, icon, feature graphic and a privacy policy.
What remains needs **you** (identity, money, Google's review). Follow the steps in order.

---

## 1. Create your Google Play developer account (one-time $25)

1. Go to <https://play.google.com/console/signup> and sign in with the Google account that should own the app.
2. Choose **Personal** account (you) or **Organization** (needs a D-U-N-S number; skips the tester rule in step 7).
3. Pay the **$25 registration fee** and complete **identity verification** (passport/ID, address, phone). Google may take a few days.

## 2. Set up payments so the money reaches you

A paid app needs a **payments profile (merchant account)**. Ukraine is a supported merchant location, with payouts in UAH.

1. Play Console → **Setup → Payments profile** → create or link a payments profile.
2. Fill in your legal name and address exactly as on your ID, and your **tax information** (W-8BEN for non-US individuals, which claims treaty benefits where applicable).
3. Add your **bank account** for payouts (IBAN/SWIFT). Google sends a small test deposit to verify it.
4. Payouts arrive monthly once your balance passes the threshold.

**What you receive from a $1 sale:** Google keeps a **15%** service fee (on your first $1M per year). Where Google collects VAT/GST on your behalf (EU, UK and others), that tax comes off first. So a $0.99 sale typically pays you **≈ $0.70–0.85**.

## 3. Signing: reuse your existing release key

The Play version is signed with **the same private key** as the GitHub APK (it's already in your GitHub secrets). That way someone who installed the free APK can later install from Play, or the other way round, without uninstalling.

In Play Console, when it asks about app signing (Setup → App signing, or during the first release), choose **"Use existing app signing key" → "Export and upload a key from Java keystore"**:
1. Download `pepk.jar` and the encryption public key from that page.
2. On a computer with Java, next to your `holos-release-2026.jks`, run the command the page shows. It looks like:
   `java -jar pepk.jar --keystore=holos-release-2026.jks --alias=holos --output=holos-signing.zip --include-cert --rsa-aes-encryption --encryption-key-path=encryption_public_key.pem`
   Enter your keystore password when it asks.
3. Upload `holos-signing.zip`. When it asks for an upload key, choose the same key.

## 4. Build the App Bundle

GitHub → **Actions → "Google Play bundle" → Run workflow** (leave "upload" unchecked).
Download the artifact: `app-play-release.aab` (upload this) and `mapping.txt`.

The Play build has **no self-updater and no donation links**. Google forbids both in Play apps: updates must come from Play, and payments for the app must go through Google. Do **not** mention the free GitHub version anywhere in the Play listing.

## 5. Create the app in Play Console

Play Console → **Create app**:
- App name: **Holos: Offline Text to Speech** · Default language: English (United States)
- App or game: **App** · Free or paid: **Paid**. ⚠️ Choose **Paid**: a free app can never be turned into a paid one later.
- Accept the declarations.

Then go to **Setup → App signing** and keep **Play App Signing** (Google holds the app-signing key, and your upload key can be reset if lost).

## 6. Fill in the store listing and policy forms

**Store listing** (Grow → Store presence → Main store listing). Copy the texts from `fastlane/metadata/android/en-US/` and add the **Ukrainian** translation from `fastlane/metadata/android/uk/`:
- App icon: `fastlane/metadata/android/en-US/images/icon.png` (512×512)
- Feature graphic: `fastlane/metadata/android/en-US/images/featureGraphic.png` (1024×500)
- **Phone screenshots: take 4–8 yourself** (Library, Player with highlighted text, chapter list, language picker, New recording). Use a clean recording with an interesting text. 1080×2400 screenshots from your Nothing Phone are fine.
- Category: **Books & Reference** (or Productivity) · Contact email: required, use a dedicated one.

**App content** (Policy → App content), answers that match this app:
- **Privacy policy URL:** a public page with `PRIVACY.md` (see step 9 if you make the repo private).
- **Ads:** No ads.
- **App access:** All functionality available without special access.
- **Content rating:** fill the IARC questionnaire (utility app, no violence, no user-to-user interaction) → "Everyone"/PEGI 3.
- **Target audience:** 13+ (or 18+). Not designed for children.
- **Data safety:** *No data collected, no data shared*. Data is not encrypted in transit because none is sent; model downloads contain no user data. No account creation, so no account-deletion URL is needed.
- **Foreground service permissions** (asked because of target API 34+): declare
  - `mediaPlayback`: "plays generated speech in the background with lock-screen controls";
  - `mediaProcessing` (Android 15+) and `dataSync` (Android 14 and older): "converts the user's text to audio on-device in the background; the user starts it and sees a progress notification".

  Google may ask for a short screen recording that shows the notification while generating. Record one on your phone and upload it to YouTube as unlisted.
- **Government apps / financial features / health:** No.

**Pricing** (Monetize → Products → App pricing): set **USD 0.99** (or 1.00) and let Play convert local prices. Prices set this way are "buy once, use forever"; there is no subscription.

**Countries:** select all, or at least Ukraine, EU, US/UK.

## 7. Testing requirement (personal accounts created after 13 Nov 2023)

Before you can publish to Production, you must run a **closed test with at least 12 testers, opted in continuously for 14 days**:
1. Testing → **Closed testing** → create track → upload `app-release.aab` → add testers (an email list or Google Group) → roll out.
2. Send testers the opt-in link. ⚠️ On closed tracks testers **must buy a paid app** too. Give them free copies with **promo codes**: Monetize → Promotions → **Create promotion** → promo code, for "Holos" (paid apps get up to 500 codes per quarter). Each tester redeems a code in the Play Store and then installs from the opt-in link.
   - Ideal testers: 15–20 students from the uni chat (a few extra in case some drop out). They must stay opted in and keep the app installed for 14 days, and should actually open it now and then.
3. After 14 days with ≥12 testers, **Apply for production access** in the dashboard and answer the questions about your testing.

Organization accounts skip this step and can go straight to Production.

## 8. Release to Production

Production → **Create new release** → upload the `.aab` (or promote the closed-test release) → release notes from `changelogs/default.txt` → **Review and roll out**. The first review usually takes a few days.

**Later updates:** run the "Google Play bundle" workflow again (each build gets a higher version code automatically) and upload the new `.aab`. Optionally, create a service account with access to the Play Developer API and add its JSON as the `PLAY_SERVICE_ACCOUNT_JSON` secret. After that, "upload" in the workflow pushes a draft release for you.

## 9. Before you start selling

- **Free version:** the free GitHub APK, with donations and the self-updater, stays available. That's allowed. Just keep it out of the Play listing and the Play build. Your Monobank jar description can point donors to it: «Дякую! Holos безкоштовно: https://github.com/Pixtane/transcribe-local-apk/releases/latest».
- **Privacy policy URL:** if you make the repo private, host the policy somewhere public. A public GitHub Gist with the contents of `PRIVACY.md` works, or a GitHub Pages site. Then update `PRIVACY_URL` in `app/src/main/java/com/pixtane/holos/ui/screens/AboutSection.kt`.
- **Model licenses (please verify yourself):**
  - Supertonic 3 is under **OpenRAIL-M**, which allows commercial use. You must pass on its use restrictions, which the app shows under Settings → About.
  - The sherpa-onnx exports are Apache-2.0.
  - The Coqui/Neon VITS voices are listed as BSD-3-Clause. Check each model card before selling.
  - Do **not** add Meta MMS voices to a paid app: they are CC-BY-NC (non-commercial).
- **Name:** search Play for "Holos" to avoid confusion with an existing trademark. A distinctive subtitle (as in the title) helps.
- **One key:** both versions are signed with `holos-release-2026.jks`. Back it up. Users can't update a GitHub-installed APK from Play (different signature): they need to uninstall it first.
