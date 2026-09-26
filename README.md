# minecraft-ingress-queue

TCPShield 뒤에 마인크래프트 네트워크를 두면서 만든 입구 서버와 입장 대기열입니다.

TCPShield에서 경로(백엔드 호스트)를 여러 개로 나눠 쓰려고 만들었습니다. 하고 싶었던 건 세 가지였어요.
트래픽을 경로마다 고르게 나누기, 경로 주소로 바로 들어오는 우회 접속 막기, 정원이 찼을 때 줄 세우기.
그래서 플러그인 세 개가 됐습니다.

- `router/` IngressRouter (Paper/Folia): 모든 접속이 처음 들어오는 곳입니다. 월드에 들여보내지 않고,
  덜 쓰인 경로를 골라 입장표를 쿠키에 심은 뒤 그 주소로 Transfer 합니다.
- `proxy/` FairQueue (Velocity): 입장표를 확인하고, 정원을 지키면서 대기열을 돌립니다.
- `lobby/` FairQueueLobby (Paper): 기다리는 동안 머무는 빈 공허 월드입니다.

```
클라이언트 → entry.example.com → TCPShield → IngressRouter
          ↳ (Transfer) → s1~s10.example.com → TCPShield → Velocity + FairQueue → 메인 서버 / 대기 로비
```

게임 안 문구는 한국어입니다.

## 경로 고르기

접속자 수로 나누면 잘 안 맞습니다. 청크를 계속 새로 받는 사람과 가만히 있는 사람은 대역폭이 몇 배씩 차이 나서요.
그래서 Velocity가 경로마다 실제로 보낸 바이트를 누적해 두고, 라우터는 그중 가장 적게 쓴 경로로 새 사람을 보냅니다.

```
load = 누적 송신 바이트 + 256 KiB × 아직 도착 안 한 예약 수
```

예약분을 더하는 건 여러 명이 동시에 들어올 때 아직 도착 전인 사람들까지 한 경로로 몰리지 않게 하려는 겁니다.
최근 초당 속도는 순간순간 흔들림이 커서 누적값을 씁니다. 누적값은 1초마다 파일에 저장해서 재시작해도 이어집니다.

라우터는 0.5초마다 Velocity의 경로 API(127.0.0.1)를 읽습니다. 그래서 라우터와 Velocity는 같은 머신에 있어야 합니다.
API가 한동안 응답이 없으면 마지막으로 받은 값으로 고르고, 그것도 오래되면 그냥 순서대로 돌립니다.

## 입장표

라우터가 만든 쿠키(`ingress:entry`)를 Velocity가 확인합니다. 들어가는 건 버전, 일회용 nonce, 발급·만료 시각(30초),
소문자 닉네임, 경로 호스트, 그리고 이 전체에 대한 HMAC-SHA256입니다. 키는 32바이트 파일 하나를 둘이 같이 씁니다.

```bash
head -c 32 /dev/urandom > entry-ticket.key && chmod 600 entry-ticket.key
```

Velocity 쪽에서는 이렇게 막습니다.

- Transfer로 들어온 접속이 아니면 인증 전에 끊고 입구 주소로 안내합니다. 경로 주소 직접 접속은 여기서 걸립니다.
- 서명, 만료, 닉네임과 접속한 호스트가 표와 맞는지, 이 프로세스가 켜진 뒤에 발급된 표인지 확인합니다.
- nonce는 한 번만 받습니다. 쌓인 기록이 1만 개를 넘으면 새 접속을 받지 않는 쪽으로 실패합니다.

입장표는 "30초 안에 라우터를 거쳐 왔다"는 영수증이지 신원 증명은 아닙니다. 라우터는 오프라인 모드라 아무 닉네임으로나
표를 받을 수 있습니다. 신원은 Velocity 정품 인증이 확인하니, 남의 닉네임으로 받은 표는 그 계정이 없으면 못 씁니다.
쿠키는 암호화하지 않고 무결성만 지킵니다. 대량 접속 공격은 TCPShield가 막아야 할 몫입니다.

## 대기열

메인 서버로 연결을 시작하는 순간부터 자리를 차지한 것으로 세기 때문에, 한꺼번에 몰려도 정원을 넘지 않습니다.
(150명이 동시에 들어와도 정확히 100명만 들어가는지 테스트가 있습니다.) 빈자리는 기다리지 않고 바로 채웁니다.

우선 입장 대상(`priority.json`에 있는 사람)이 있으면 우선 3명, 일반 1명 순서로 섞습니다.
우선 대상이 아무리 많아도 일반 대기는 네 번에 한 번은 들어갑니다.

예상 대기 시간은 정원이 꽉 차서 실제로 줄을 서던 시간 동안의 입장 속도로 계산합니다.

```
분당 입장 = 최근 20분 입장 수 / max(바쁜 시간(분), 2)       입장이 3번 미만이면 "계산 중"
예상 시간 = ceil(순번 × 60 / 분당 입장)
```

빈자리로 바로 들어간 입장은 세지 않습니다. 그걸 세면 한가할 때 속도가 부풀어서, 줄이 생기는 순간 예상 시간이 너무 짧게 나옵니다.
EWMA 대신 20분 창을 쓴 건, 조정할 상수가 없고 로그를 보고 손으로 검산할 수 있어서입니다.

일반 대기 중인 사람에게는 "우선 입장 대상은 지금 들어와도 N번째예요"라는 안내를 가끔 보냅니다.
실제 대기열 규칙과 같은 계산을 쓰고, 차이가 3칸 이상일 때만, 최대 3분에 한 번 보냅니다.

## 설정

라우터는 `plugins/IngressRouter/config.yml`입니다.

```yaml
ticket-key-file: entry-ticket.key
proxy-api: { host: 127.0.0.1, port: 8765, poll-interval-ms: 500, stale-after-ms: 5000 }
pending-timeout-ms: 10000
server-list: { backend-port: 25566, max-players: 100 }
targets:
  - { host: s1.example.com, port: 25565, enabled: true }
  # ... s10.example.com 까지
```

FairQueue는 처음 켜면 `plugins/fairqueue/config.properties`를 만듭니다. 주로 바꾸는 건 `capacity`, `main-server`,
`lobby-server`, `entry-host`, `route-hosts`, `ticket-key-file`, `priority-file` 정도입니다.
`access-root`에 메인 서버 폴더를 적으면 화이트리스트·차단·관리자 목록을 거기서 읽어 옵니다.

서버 쪽에서 꼭 켜야 하는 것도 있습니다.

- 라우터: `online-mode=false`, `enforce-secure-profile=false`, TCPShield 뒤라면 Paper의 `proxy-protocol: true`
- Velocity: `online-mode = true`, modern forwarding, `accepts-transfers = true`, `haproxy-protocol = true`
- 메인 서버와 로비는 127.0.0.1에만 열거나 방화벽으로 막으세요. 로비는 `bukkit.yml`에서 생성기를 `FairQueueLobby`로.
- TCPShield에는 entry 주소를 라우터로, s1~s10을 Velocity로 연결합니다.

## 빌드

JDK 25. FairQueue가 Velocity 내부 클래스를 써서 공개 API만으로는 안 되고, 프록시 jar가 필요합니다.

```bash
./gradlew build -PvelocityJar=/path/to/velocity.jar
```

Paper API 26.2, Velocity 4.2.1-SNAPSHOT으로 확인했습니다. 테스트는 `assert`를 쓰는 `main` 프로그램들이고 `build`에서 같이 돕니다.
Gradle 없이 하려면 `scripts/build-local.sh`를 보세요.

## 아직 안 되는 것

메인 서버는 하나만 지원합니다. 죽은 경로를 자동으로 빼지 못해서 `enabled: false`로 두고 `router reload` 해야 합니다.
대기 순번은 프록시 메모리에만 있어서 Velocity를 재시작하면 줄이 비워집니다. 쿠키와 Transfer를 쓰니 1.20.5 이상 클라이언트만 됩니다.

## English

Entry router + fair admission queue for a Minecraft network behind TCPShield. The Paper router sends each player to the
least-used of up to ten routes (by cumulative bytes the proxy actually sent) and hands over a one-use HMAC entry ticket in a
cookie; the Velocity plugin rejects anything that skipped the router, keeps a hard seat cap, mixes a priority lane 3:1,
and estimates wait time from real admissions while the server was full. GPL-3.0.
