# BT Woofer Audio Link

Stream live Windows system audio from a desktop browser to an Android phone over local Wi-Fi. The phone plays it through its default output, including a headphone/aux jack.

## Use

1. Connect the Windows PC and Android phone to the same non-guest Wi-Fi network. In the receiver app, tap **Start listening** and note the phone's displayed IPv4 address. The receiver uses port `50005`.
2. On the PC, open [https://mirvo19.github.io/bt-speaker/](https://mirvo19.github.io/bt-speaker/) in Chrome or Edge. Enter the phone's IP and port.
3. The receiver creates a local, self-signed TLS certificate. Before connecting from the sender page, open `https://<phone-IP>:50005` in a new Chrome tab and proceed through the local certificate warning, then return to the sender page. This approval is needed because GitHub Pages is HTTPS and browsers block its connection to an unencrypted local WebSocket. The certificate is regenerated when listening starts, so approve it again after restarting the receiver.
4. Press **Connect**. In the browser sharing prompt, choose **Entire Screen** and turn on **Share audio**, then confirm sharing. A missing audio track is shown as an error. Press **Disconnect** to stop.
5. Connect the phone's headphone output to the speaker system. Keep the receiver app open while listening.

The web page is static HTML and JavaScript; it has no backend. The GitHub Actions workflow publishes it to Pages from `main`. For first-time Pages setup, either set **Settings > Pages > Build and deployment > Source** to **GitHub Actions**, or add a `GH_PAGES_ADMIN_TOKEN` Actions secret containing a token authorized to enable Pages (classic PAT with `repo` scope, or a fine-grained PAT with Pages write and Administration write). When configured, this token is used by the Pages configuration action; the deployment itself uses the workflow's Pages and OIDC permissions.

## Compatibility and limits

- System-wide capture with `getDisplayMedia` is supported here in Chrome or Edge on Windows only, and requires **Entire Screen** plus **Share audio**. A browser permission prompt is required for each session.
- The receiver app supports Android 7.0 (API 24) and later. The APK is built on every push to `main`, uploaded as an Actions artifact, and attached to a GitHub Release. Download `BT-Woofer-Audio-Link-debug.apk` from the latest Actions run or Releases page and sideload it; Android build tools are not needed locally.
- Audio uses 44.1 kHz, stereo, signed 16-bit little-endian PCM, sent in 1024-frame binary WebSocket messages (about 23 ms each). The phone listens on TCP port `50005` using TLS-secured WebSockets and plays through the default Android audio route. Only one browser sender is accepted at a time.
- Expect roughly 100-300 ms of end-to-end latency, varying with browser capture, Wi-Fi, phone audio buffers, and output hardware. This is a listening link, not a synchronized or lossless recording system.
- The receiver's certificate is self-signed and has no authentication. Approve it only on a trusted local network. Keep the phone and PC on the same LAN; guest Wi-Fi or client isolation, firewall rules, or VPN routing may prevent the connection.
- Browser and Android audio devices may resample internally. Protected or otherwise restricted system audio may not be capturable. The receiver app must remain open, and restarting it requires approving its newly generated certificate again.

## CI outputs

`.github/workflows/build.yml` builds and tests the Android debug APK, runs Android lint, uploads the APK as a downloadable artifact, publishes a GitHub Release for pushes to `main`, and deploys the static sender page to GitHub Pages. Workflow steps have explicit names and retain Gradle stack traces to make CI failures easier to diagnose.
