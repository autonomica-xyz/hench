# Hench script tools

A script tool is a directory with `manifest.json` + `script.py`. Drop it in
`/Android/data/net.nostr.hench/files/tools/<name>/` (USB or file manager)
or import a zip via Settings -> Advertised tools -> Import. Enable it on the
same settings page; the worker restarts and advertises it on Nostr like any
built-in tool, paid in sats over the CEP-8 gate.

The script runs on the bundled Python 3.12 (stdlib + curl_cffi installed) as
a subprocess with a hard timeout. argv[1] is the JSON arguments; print the
result to stdout (output: "text") or write the artifact to the path in
argv[2] (output: "file" — it gets uploaded to Blossom and returned as a link).

Scripts run with full app access. Only install tools you trust — like apps.
