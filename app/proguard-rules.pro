# 타입 안전 내비게이션(Routes.kt)과 업데이트 매니페스트(UpdateManifest.kt)는
# kotlinx.serialization이 클래스 이름·직렬화기를 런타임에 찾으므로 이름과 멤버를 유지한다.
-keep,includedescriptorclasses class com.leepy0.photocull.ui.nav.** { *; }
-keep,includedescriptorclasses class com.leepy0.photocull.update.** { *; }
-keepclassmembers @kotlinx.serialization.Serializable class com.leepy0.photocull.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisibleAnnotations
