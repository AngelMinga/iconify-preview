# Privacy Policy — Iconify Icon Preview

_Last updated: October 28, 2026 (version 0.1.0)_

Iconify Icon Preview ("the plugin") is a JetBrains IDE plugin that shows [Iconify](https://iconify.design) icons next to
their names in the editor. This page explains what data it handles and where it goes.

## Summary

- The plugin **does not collect or send any data to the plugin author**.
- It has **no telemetry, analytics or tracking**.
- To draw icons, it downloads them from the **Iconify API** (`https://api.iconify.design` by default). The requests
  contain icon names, not your code.

## What is sent, and when

While previews are enabled, the plugin looks for quoted icon names such as `"mdi:home"` in the files you open. It then
makes these requests to the Iconify API:

- `GET /collections`: the list of icon sets, so only real icon names are previewed. Once, then at most once a week.
- `GET /<prefix>/<name>.svg`: one request per icon name found in your files, the first time it is seen. For example,
  opening a file that contains `"mdi:home"` requests `/mdi/home.svg`.

Nothing else from your files is sent: no file contents, file names, paths or project names. As with any web request,
the API server sees your IP address and the IDE's name and version (sent as the user agent). Requests go through the
IDE's proxy settings.

## Who receives it

The Iconify API is a third-party service run by the Iconify project, not by the plugin author. How it handles requests
is governed by its own terms and policies; see [iconify.design](https://iconify.design).

You can point the plugin at your own Iconify API server in _Settings → Tools → Iconify Icon Preview_, in which case
requests go only to that server.

## What is stored on your computer

Downloaded icons and the list of icon sets are cached in the IDE's system directory
(`<system dir>/iconify-preview/`), so each icon is downloaded once and previews keep working offline. The cache only
contains public icon files. You can delete that folder at any time; it is rebuilt when needed.

Settings (on/off and the API URL) are stored in the IDE's configuration as `iconifyPreview.xml`.

## How to stop it

Uncheck _Show icon previews in the editor_ in _Settings → Tools → Iconify Icon Preview_, or disable or uninstall the
plugin. No more requests are made.

## Contact

Questions or concerns: open an issue at
[github.com/AngelMinga/iconify-preview](https://github.com/AngelMinga/iconify-preview/issues).
