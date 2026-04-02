# MasterDnsVPN Android Client
🌐 **[فارسی](README_fa.md)** 
An Android client for the **MasterDnsVPN** ecosystem.

- **Upstream project (original)**: https://github.com/masterking32/MasterDnsVPN
- **Note**: This repository is a client-side Android app built on top of the upstream Go core.
  

## Download & install

1. Go to this repository’s **Releases** page.
2. Download the latest **`universal`** APK (recommended).
3. Install it on your phone.

If Android blocks installation, enable **“Install unknown apps”** for your browser/file manager.

## Quick start (in-app)

You need a running MasterDnsVPN server (domain + key) to use the client.

Inside the app, fill the required fields:

- **`DOMAINS`**: your tunnel domain (example: `v.example.com`)
- **`ENCRYPTION_KEY`**: the shared key from the server
- **Resolvers**: add one or more DNS resolvers (IP or IP:PORT)

Then start the VPN.

## What this app does

- Creates an Android VPN service and routes traffic through the MasterDnsVPN tunnel
- Provides a local proxy mode (SOCKS/TCP) depending on your configuration

## Security notes

- Only install APKs from this repository’s **Releases**.
- Keep your **server encryption key** private.
- If you share configuration screenshots, redact the encryption key and any credentials.

## Troubleshooting

- **App won’t update / “App not installed”**: uninstall older builds signed with a different key, then install the new one.
- **Connects but no traffic**: re-check `DOMAINS`, resolver list, and that the server is reachable and correctly configured.
- **VPN toggles off immediately**: check Android VPN permission prompts and battery restrictions.

## License

MIT. See `LICENSE`.

## Credits

Based on the upstream MasterDnsVPN project:

- https://github.com/masterking32/MasterDnsVPN
