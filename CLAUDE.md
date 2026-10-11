# PhotoCull 개발 규칙

카메라 SD 카드 → 폰 이동 + 흐림/연사 정리 Android 앱. 기능 명세는 `docs/DESIGN.md`, 원래 계획은 `PLAN.md`.

## 빌드·테스트

```bash
./gradlew :app:compileDebugKotlin                 # 빠른 컴파일 확인
./gradlew :app:testDebugUnitTest                  # 단위 테스트(JVM + Robolectric)
./gradlew :app:testDebugUnitTest --tests '*Screenshot*' -Proborazzi.test.record=true
#   → app/build/outputs/roborazzi/*.png (화면 스크린샷, Read 도구로 눈으로 확인)
./gradlew :app:assembleRelease                    # APK (서명 키 환경변수 없으면 디버그 키)
```

- 클라우드 컨테이너에서는 Android SDK가 `/opt/android-sdk`(local.properties의 sdk.dir), Maven Central은 `~/.gradle/init.d`의 미러를 쓴다. 저장소 설정 파일은 표준 그대로 둔다.
- 기기·에뮬레이터가 없다. 화면은 Robolectric 스크린샷으로, 로직은 JVM 단위 테스트로 검증한다.

## 코드 규칙

- Kotlin, Jetpack Compose, Material 3. 단일 모듈, 패키지로 계층 분리(`data` / `storage` / `importer` / `analysis` / `trash` / `update` / `ui`).
- DI는 수동: `AppContainer`(앱 전역 객체). 화면에서는 `LocalAppContainer.current`, ViewModel은 `viewModel { XViewModel(container) }`.
- 화면 = `XScreen`(ViewModel 연결) + `XContent`(상태와 콜백만 받는 stateless). 스크린샷 테스트는 `XContent`에 가짜 상태를 넣어 찍는다.
- 화면 상태는 Loading / Empty / Error / Content 4상태를 모두 그린다(`ui/components/StateViews.kt`).
- 색·간격·모양은 토큰만 쓴다: `MaterialTheme.colorScheme`, `LocalStatusColors`, `Spacing`, `MaterialTheme.shapes`, `MaterialTheme.typography`. 화면 코드에 `Color(0x..)`, `16.dp`, `14.sp` 하드코딩 금지(토큰 파일 제외).
- 간격은 4의 배수. 터치 타깃 48dp 이상. 주요 행동은 화면당 1개, 화면 하단(`BottomActionBar` + `PrimaryActionButton`).
- 색만으로 의미를 전달하지 않는다(아이콘·텍스트 병기). 삭제 대상 = 오류 색 테두리 + 체크 아이콘.
- 되돌릴 수 있는 동작(휴지통 이동)은 확인 없이 실행 + 스낵바 "실행 취소". 되돌릴 수 없는 동작(영구 삭제)만 확인 대화상자, 버튼은 동사("12개 영구 삭제" / "취소").
- 사용자에게 보이는 문구는 한국어, 기능별 `res/values/strings_<기능>.xml`에 둔다(공통은 `strings.xml`). 예외 메시지 원문을 화면에 노출하지 않는다.
- 파일 작업은 `Dispatchers.IO`, 분석은 `Dispatchers.Default`. 메인 스레드에서 디스크·네트워크 금지.

## 데이터 안전 원칙 (가져오기·휴지통)

- 카드 원본은 폰 복사본의 해시 검증이 끝나기 전에는 절대 지우지 않는다.
- 복사 중 파일은 `*.photocull.part`. 이 접미사 파일만 정리 대상.
- 사진 "삭제"는 휴지통(`DCIM/.PhotoCullTrash/`) 이동. 영구 삭제는 휴지통 화면에서만.

## 배포 (자동 업데이트)

- 버전은 `version.properties`의 `versionName` 하나(versionCode는 자동 계산). 올려서 main에 push(병합)하면 CI(`.github/workflows/android.yml`)가 `vX.Y.Z` 정식 릴리스를 만든다. 다른 브랜치는 빌드·테스트만 한다.
- 릴리스 자산: `PhotoCull.apk`, `PhotoCull.apk.sha256`, `PhotoCull.json`(매니페스트). 앱은 `releases/latest/download/PhotoCull.json`으로 새 버전을 확인한다.
- 서명 키는 저장소에 없다. CI는 GitHub Secrets `PHOTOCULL_KEYSTORE_BASE64`, `PHOTOCULL_KEYSTORE_PASSWORD`로 서명한다. 같은 키로 서명돼야 설치된 앱이 업데이트된다.
- 같은 버전 번호로 다시 릴리스하지 않는다. 테스트 빌드를 정식 릴리스로 올리지 않는다.
