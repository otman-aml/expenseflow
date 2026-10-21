# ---------- Service nginx direct sur dist/ ----------
# Ce Dockerfile ne build PAS le frontend : il sert le repertoire dist/ genere
# en local par `npm run build`. Le build dans Docker (npm ci) est bloque par le
# demon Docker de cette machine, et le dist/ local est deja valide (build + eslint OK).
#
# L'ancien Dockerfile multi-etapes (node puis nginx) est conserve dans
# Dockerfile.node-build pour reference.

FROM nginx:1.27-alpine

# La configuration remplace celle de l'image : sans cela, toute URL inconnue
# (=/notes/new au rafraichissement d'une page) renvoie 404, et l'utilisateur
# voit une page blanche apres avoir recharge.
COPY nginx.conf /etc/nginx/conf.d/default.conf

# Le dist/ copie est celui genere localement : il doit contenir les cles
# expenseflow, expenseflow.local, expenseflow.remember-email et expenseflow.ma
# (et surtout plus expenseflow.session, cle retiree par le correctif "remember me").
COPY dist/ /usr/share/nginx/html/

# Utilisateur non root : nginx n'a aucune raison de tourner en root, et un
# conteneur compromis ne doit pas disposer de privileges systeme.
#
# `/var/cache/nginx` manque dans certaines versions de l'image : d'ou le
# `|| true`, qui ne fait pas echouer le build si le repertoire est absent.
RUN chown -R nginx:nginx /var/cache/nginx 2>/dev/null || true
RUN touch /var/run/nginx.pid && chown nginx:nginx /var/run/nginx.pid
USER nginx

EXPOSE 8080

# Sonde de disponibilite : / est un fichier statique servi par nginx, sans
# passer par l'API. Elle repond donc des que le front est pret.
#
# 127.0.0.1 et non "localhost" : BusyBox resout "localhost" en IPv6 (::1)
# alors que nginx n'ecoute qu'en IPv4, et la connexion est refusee.
HEALTHCHECK --interval=15s --timeout=5s --start-period=10s --retries=3 CMD wget -qO- http://127.0.0.1:8080/ || exit 1

CMD ["nginx", "-g", "daemon off;"]