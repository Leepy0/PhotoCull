# PhotoCull

카메라 SD 카드를 폰에 꽂으면 사진·영상을 폰으로 **옮기고**, 흐린 사진과 연사 사진을 골라 보여줘서 빠르게 정리하게 해주는 Android 앱 (개인용, Canon EOS R10 + Galaxy Z Fold8 기준).

- 설계 명세: [docs/DESIGN.md](docs/DESIGN.md) — 원래 계획([PLAN.md](PLAN.md))에서 바꾼 점과 이유 포함
- 개발 규칙: [CLAUDE.md](CLAUDE.md)

## 설치

1. [최신 릴리스](https://github.com/Leepy0/PhotoCull/releases/latest)에서 `PhotoCull.apk`를 받아 설치합니다.
   - 바로 받기: https://github.com/Leepy0/PhotoCull/releases/latest/download/PhotoCull.apk
2. 처음 실행하면 **모든 파일 접근**(필수)과 **알림**(권장)을 허용합니다.
3. 이후 업데이트는 앱이 알아서 확인합니다(아래).

## 사용 흐름

1. USB-C 카드 리더에 카메라 SD 카드를 꽂아 폰에 연결 → "카메라 카드가 연결됨" 알림
2. 알림의 **가져오기** 또는 앱의 가져오기 화면에서 시작 → 파일마다 복사 → 해시 검증 → 카드 원본 삭제
   - 화면을 꺼도 계속되고, 중간에 카드를 뽑아도 옮긴 파일은 안전합니다. 다시 꽂으면 남은 파일부터 이어서 가져옵니다.
3. 가져온 사진은 바로 분석됩니다(선명도·연사 묶음). **검토** 탭에서 흐림 / 연사 / 영상 순으로 정리
4. 삭제한 사진은 **휴지통**에 30일 보관(설정에서 변경), 언제든 복원
5. 카드를 뽑기 전에는 알림창이나 설정에서 USB 저장소를 **꺼내기** 하세요

저장 위치: `DCIM/R10/` (설정에서 폴더 이름·월별 하위 폴더 변경), 휴지통: `DCIM/.PhotoCullTrash/`

## 자동 업데이트

- 앱이 시작할 때(6시간 간격)와 하루 한 번 백그라운드에서 GitHub 최신 릴리스를 확인합니다.
- 새 버전이 있으면 알림/홈 화면의 **업데이트** 칩 → 앱이 직접 내려받아 SHA-256을 검증한 뒤 설치합니다.
- 처음 한 번은 "이 출처의 앱 설치 허용"을 켜야 합니다(업데이트 화면에서 안내).
- 설정 → 앱 정보에서 자동 확인을 끄거나 수동으로 확인할 수 있습니다.

### 릴리스 만드는 법 (개발자)

1. `version.properties`의 `versionName`을 올립니다 (예: `0.1.0` → `0.1.1`).
2. push하면 GitHub Actions가 테스트·빌드 후 `v0.1.1` 정식 릴리스를 만들고 `PhotoCull.apk`, `PhotoCull.apk.sha256`, `PhotoCull.json`(매니페스트)을 올립니다.
3. 설치된 앱은 `releases/latest/download/PhotoCull.json`을 보고 업데이트합니다.

### 서명 키 (최초 1회 설정)

설치된 앱을 업데이트하려면 모든 APK가 **같은 키**로 서명돼야 합니다. 키는 공개 저장소에 두지 않고 GitHub Secrets에 둡니다.

저장소 Settings → Secrets and variables → Actions → New repository secret:

| 이름 | 값 |
|---|---|
| `PHOTOCULL_KEYSTORE_BASE64` | 키스토어 파일(`photocull-release.p12`)을 base64로 인코딩한 문자열 |
| `PHOTOCULL_KEYSTORE_PASSWORD` | 키스토어 비밀번호 |

Secrets가 없으면 CI는 빌드·테스트만 하고 릴리스는 건너뜁니다(경고 표시). 키스토어 파일과 비밀번호는 안전한 곳에 따로 보관하세요. 잃어버리면 기존 설치본을 지우고 새로 설치해야 합니다.

## 빌드

```bash
./gradlew :app:testDebugUnitTest      # 단위 테스트 + 화면 스크린샷 테스트
./gradlew :app:assembleRelease        # APK (PHOTOCULL_KEYSTORE, PHOTOCULL_KEYSTORE_PASSWORD 환경변수가 있으면 정식 키로 서명)
```
