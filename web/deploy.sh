#!/bin/sh
# Deploys web/app to wikiponder.com/navia/app (and web/index.html to /navia/ when called with --root).
set -e
cd "$(dirname "$0")"
node --check app/app.js && node --check app/sound.js && node --check app/sw.js
tar czf /tmp/navia_app.tgz -C app .
scp -q -P 2200 -i ~/.ssh/wikipond /tmp/navia_app.tgz wikipond@wikiponder.com:navia_app.tgz
[ "$1" = "--root" ] && scp -q -P 2200 -i ~/.ssh/wikipond index.html wikipond@wikiponder.com:public_html/navia/index.html
ssh -p 2200 -i ~/.ssh/wikipond wikipond@wikiponder.com 'cd ~/public_html/navia/app && tar xzf ~/navia_app.tgz && rm ~/navia_app.tgz && find . -type d -exec chmod 755 {} + && find . -type f -exec chmod 644 {} + && chmod 644 ~/public_html/navia/index.html 2>/dev/null; echo deployed'
