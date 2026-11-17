#!/bin/sh
# Deploys web/app to wikiponder.com/navia/app (and web/index.html to /navia/ when called with --root).
# --admin deploys only web/admin to /navia/admin.
set -e
cd "$(dirname "$0")"
if [ "$1" = "--admin" ]; then
	# Admin page only. Its private config and database stay in ~/navia_admin on the server.
	tar czf /tmp/navia_admin.tgz -C admin .
	scp -q -P 2200 -i ~/.ssh/wikipond /tmp/navia_admin.tgz wikipond@wikiponder.com:navia_admin.tgz
	ssh -p 2200 -i ~/.ssh/wikipond wikipond@wikiponder.com 'mkdir -p ~/public_html/navia/admin && cd ~/public_html/navia/admin && tar xzf ~/navia_admin.tgz && rm ~/navia_admin.tgz && chmod 755 . && chmod 644 * .htaccess && for f in *.php; do php -l $f >/dev/null || exit 1; done; echo deployed admin'
	exit
fi
node --check app/app.js && node --check app/sound.js && node --check app/sw.js
tar czf /tmp/navia_app.tgz -C app .
scp -q -P 2200 -i ~/.ssh/wikipond /tmp/navia_app.tgz wikipond@wikiponder.com:navia_app.tgz
[ "$1" = "--root" ] && scp -q -P 2200 -i ~/.ssh/wikipond index.html og-image.png wikipond@wikiponder.com:public_html/navia/
ssh -p 2200 -i ~/.ssh/wikipond wikipond@wikiponder.com 'cd ~/public_html/navia/app && tar xzf ~/navia_app.tgz && rm ~/navia_app.tgz && find . -type d -exec chmod 755 {} + && find . -type f -exec chmod 644 {} + && chmod 644 ~/public_html/navia/index.html ~/public_html/navia/og-image.png 2>/dev/null; echo deployed'
