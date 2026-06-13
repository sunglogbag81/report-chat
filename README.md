# ReportChat

마인크래프트 Paper/Bukkit 서버용 **한국어 신고·관리 상담 플러그인**입니다.

## 기능

- `/report` 또는 `/report <내용>`으로 신고 접수
- 신고 내용이 없어도 신고 가능
- 설정된 관리자, OP, `report.admin` 권한자에게 신고 알림 전달
- 신고 알림 수신 시 효과음 재생
- 관리자 알림에 `[개인 채팅 생성]` 클릭 버튼 표시
- 버튼 클릭 시 신고자와 관리자 사이의 개인 상담 채팅 생성
- 상담 채팅 참여자의 일반 채팅은 해당 상담 채팅 참여자에게만 전달
- 관리자가 특정 플레이어를 상담 채팅에 강제 초대 가능
- 관리자가 특정 플레이어를 상담 채팅에서 강제 내보내기 가능
- 관리자가 상담 채팅 삭제 가능
- 관리 행동 콘솔 로그 기록
- 신고 쿨다운, 상담 채팅 자동 종료, 사운드, 관리자 목록 설정 가능

## 호환성

- Java 21
- Paper API `1.21.8-R0.1-SNAPSHOT` 기준 빌드

요청된 `26.1.2` 버전 표기는 공식 마인크래프트 Java 버전 표기와 달라서, 현재는 Bukkit/Paper 계열의 안정적인 API 위주로 구현했습니다. 대상 서버가 Bukkit/Paper 플러그인을 지원하고 Java 21 이상을 사용하면 적용 가능성이 높습니다.

## 빌드

```bash
mvn package
```

빌드 결과:

```text
target/ReportChat-1.0.1.jar
```

## 설치

1. `target/ReportChat-1.0.1.jar` 파일을 서버의 `plugins/` 폴더에 넣습니다.
2. 서버를 시작하거나 재시작합니다.
3. `plugins/ReportChat/config.yml`을 서버 상황에 맞게 수정합니다.
4. 서버 안에서 `/rc reload`를 실행하거나 서버를 재시작합니다.

## 명령어

### 일반 유저

```text
/report
/report <내용>
```

### 관리자

```text
/rc open <신고번호>
/rc invite <플레이어>
/rc kick <플레이어>
/rc close [채팅번호]
/rc list
/rc leave
/rc reload
```

전체 명령어는 `/reportchat`이며, `/rc`는 별칭입니다.

## 권한

```text
report.admin   신고를 받고 상담 채팅을 관리합니다. 기본값: OP
report.reload  설정을 다시 불러옵니다. 기본값: OP
```

`config.yml`에서도 플레이어 이름 또는 UUID로 관리자를 지정할 수 있습니다.

```yaml
admins:
  - "Gamparda"
notify-op: true
```

## 기본 설정

전체 기본 설정은 [`src/main/resources/config.yml`](src/main/resources/config.yml)에 있습니다.

```yaml
admins:
  - "Gamparda"
notify-op: true

sounds:
  enabled: true
  report-received: "ENTITY_EXPERIENCE_ORB_PICKUP"
  private-chat-created: "BLOCK_NOTE_BLOCK_PLING"
  chat-message: "UI_BUTTON_CLICK"

report:
  cooldown-seconds: 30
  allow-empty-report: true

private-chat:
  one-active-chat-per-player: true
  auto-timeout-minutes: 30
  log-to-console: true
  prefix: "&8[&c신고상담&8]"
```

## 사용 흐름

1. 유저가 `/report 핵 의심` 또는 `/report`를 입력합니다.
2. 온라인 관리자에게 신고 알림과 효과음이 전달됩니다.
3. 관리자가 `[개인 채팅 생성]` 버튼을 누릅니다.
4. 신고자와 관리자 사이에 상담 채팅이 생성됩니다.
5. 이후 참여자가 일반 채팅에 입력한 메시지는 상담 채팅 참여자에게만 보입니다.
6. 관리자는 필요하면 `/rc invite <플레이어>`로 추가 인원을 강제 초대합니다.
7. 관리자는 `/rc kick <플레이어>`로 참여자를 내보내거나 `/rc close`로 채팅을 삭제합니다.

## 주의

- `private-chat.one-active-chat-per-player: true`가 기본값입니다. 한 플레이어가 여러 상담 채팅에 동시에 연결되면 일반 채팅 라우팅이 애매해지기 때문입니다.
- 비관리자 참여자는 `/rc leave`로 나갈 수 있습니다.
- 관리자는 관리 목적상 `/rc leave`가 아니라 `/rc close` 또는 `/rc kick`을 사용해야 합니다.
- `private-chat.auto-timeout-minutes`가 0보다 크면, 일정 시간 대화가 없는 상담 채팅은 자동 종료됩니다.
