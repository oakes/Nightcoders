![screenshot](resources/public/screenshot.png)

## Introduction

Nightcoders.net is a cloud IDE for ClojureScript. To successfully run it on a server, all you need to do is build the uberjar using the command below, and run it on your server with `boot.jar` (located at the root of this repo) in the same directory. It will need the JDK installed but will not need Boot installed.

That's not a joke...there is literally nothing else you need to do. Just `java -jar nightcoders.jar` and it will run. It uses H2, an embedded database, so there is nothing else to set up. Clojure is dope!

If you want to use your own Google Sign In client id (which you should...), find the existing one in these three files and change them to whatever you want:

* `resources/public/nightcoders.html`
* `resources/public/loading.html`
* `src/clj/nightcoders/core.clj`

Your server must proxy web socket connections for the code reloading to work properly. If you are using nginx, they have [documentation](https://www.nginx.com/blog/websocket-nginx/) on how to do this. Make sure `proxy_send_timeout` and `proxy_read_timeout` are set to a reasonable amount. [See this file](default) for an example of what you might want in your `/etc/nginx/sites-available/default` file.

If you are experiencing permissions errors in your control panel, it may be because the sandboxing system isn't working properly. You can disable it by commenting out [this line](https://github.com/oakes/Nightcoders.net/blob/dc2bdf3ab8cd7a93d4f53bc12788e480f1228aef/resources/template.build.boot#L22).

## Development

* Install JDK 11 or above
* Install [the Clojure CLI tool](https://clojure.org/guides/getting_started#_clojure_installer_and_cli_tools)
* To develop with figwheel: `clj -A:dev:cljs`
* To build the uberjar: `clj -A:prod:cljs uberjar`

## Licensing

All files that originate from this project are dedicated to the public domain. I would love pull requests, and will assume that they are also dedicated to the public domain.
