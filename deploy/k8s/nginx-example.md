1. Новий роутинг: podId з path /ws/{podId}/connect
   1.1. Nginx gateway (path-based замість subdomain)

Оновлюємо ConfigMap з конфігом Nginx. Головна ідея:

ловимо path: /ws/{podId}/connect

витягуємо {podId} з regex

переписуємо path на /ws/connect (щоб backend мав стабільний endpoint)

прокидуємо на http://{podId}.ws-backend.default.svc.cluster.local:8080

server_name тепер _, хост неважливий — це зручно і для minikube, і для продакшну.

```
apiVersion: v1
kind: ConfigMap
metadata:
  name: ws-gateway-nginx-conf
  namespace: default
data:
  ws-gateway.conf: |
    worker_processes auto;
    events {
      worker_connections 10240;
    }

    http {
      # DNS resolver всередині кластера
      resolver kube-dns.kube-system.svc.cluster.local valid=10s ipv6=off;

      proxy_set_header Host $host;
      proxy_set_header X-Real-IP $remote_addr;
      proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
      proxy_set_header X-Forwarded-Proto $scheme;

      map $http_upgrade $connection_upgrade {
        default upgrade;
        ''      close;
      }

      server {
        listen 80;
        server_name _;   # приймаємо будь-який Host (зручно локально)

        # /ws/{podId}/connect
        location ~ ^/ws/(?<podid>[a-zA-Z0-9-]+)/connect$ {

          # сформувати ім'я upstream: <podId>.ws-backend.default.svc.cluster.local:8080
          set $upstream "$podid.ws-backend.default.svc.cluster.local:8080";

          # переписати URL з /ws/{podId}/connect -> /ws/connect
          rewrite ^/ws/[^/]+/connect$ /ws/connect break;

          proxy_http_version 1.1;
          proxy_set_header Upgrade $http_upgrade;
          proxy_set_header Connection $connection_upgrade;

          proxy_read_timeout 600s;
          proxy_send_timeout 600s;

          proxy_pass http://$upstream;
        }

        location /healthz {
          return 200 "ok\n";
        }
      }
    }

```
