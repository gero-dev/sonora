package app.sonora.ui.screens

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** Одна страница онбординга: иконка, заголовок и короткое объяснение. */
private data class OnboardingPage(
    val icon: ImageVector,
    val title: String,
    val body: String,
)

private val OnboardingPages = listOf(
    OnboardingPage(
        icon = Icons.Rounded.AutoAwesome,
        title = "Добро пожаловать",
        body = "Sonora играет вашу музыку: локальные файлы с устройства, YouTube Music " +
            "через InnerTube и свободные каталоги Internet Archive и Jamendo. " +
            "Без рекламы и подписок.",
    ),
    OnboardingPage(
        icon = Icons.Rounded.Home,
        title = "Главная и вкладки",
        body = "Пять вкладок внизу: Главная, Поиск, История, Медиатека и Настройки. " +
            "История хранит прослушанные треки и исполнителей, как в Spotify, " +
            "а Медиатека — ваши файлы, альбомы и плейлисты.",
    ),
    OnboardingPage(
        icon = Icons.Rounded.Headphones,
        title = "Плеер",
        body = "Свайп влево или вправо переключает трек, вниз — закрывает плеер, " +
            "вверх — открывает очередь. Двойной тап по обложке ставит паузу. " +
            "Долгое нажатие на кнопку перемешивания включает стриминг или офлайн-режим.",
    ),
    OnboardingPage(
        icon = Icons.Rounded.Widgets,
        title = "Виджеты и очередь",
        body = "Очередь бесконечная: добавляйте треки, меняйте порядок и очищайте её. " +
            "На рабочий стол можно поставить виджеты 2×2, 4×1, 4×2 и 4×4.",
    ),
    OnboardingPage(
        icon = Icons.Rounded.CheckCircle,
        title = "Всё готово",
        body = "Отсканируйте фонотеку на Главной и подключите YouTube Music в Настройках. " +
            "Этот экран можно открыть снова из Настроек — там же тема и акцентный цвет.",
    ),
)

/** Вкладки нижней навигации для мини-иллюстрации второй страницы. */
private val OnboardingTabs = listOf(
    Icons.Rounded.Home to "Главная",
    Icons.Rounded.Search to "Поиск",
    Icons.Rounded.History to "История",
    Icons.Rounded.LibraryMusic to "Медиатека",
    Icons.Rounded.Settings to "Настройки",
)

/**
 * Первый запуск: 5 свайпаемых страниц «где какие кнопки» — вкладки, жесты
 * плеера, виджеты и очередь. Экран самодостаточный: показывает себя и вызывает
 * [onFinish] по «Пропустить» или «Начать»; сохранение флага — за вызывающей стороной.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    val pagerState = rememberPagerState(pageCount = { OnboardingPages.size })
    val scope = rememberCoroutineScope()
    val isLastPage = pagerState.currentPage == OnboardingPages.lastIndex

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding(),
    ) {
        // Верхняя строка: бренд и «Пропустить» (кроме последней страницы).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "SONORA",
                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 3.sp),
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.weight(1f))
            if (!isLastPage) {
                TextButton(onClick = onFinish) {
                    Text("Пропустить", style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f),
            pageSpacing = 0.dp,
        ) { pageIndex ->
            OnboardingPageContent(
                page = OnboardingPages[pageIndex],
                pageIndex = pageIndex,
            )
        }

        PageDots(
            pageCount = OnboardingPages.size,
            selectedIndex = pagerState.currentPage,
        )
        Spacer(Modifier.height(16.dp))

        // Нижние кнопки: «Назад» (со второй страницы) и «Далее»/«Начать».
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (pagerState.currentPage > 0) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage - 1)
                        }
                    },
                    modifier = Modifier
                        .weight(0.45f)
                        .height(52.dp),
                ) {
                    Text("Назад")
                }
            }
            Button(
                onClick = {
                    if (isLastPage) {
                        onFinish()
                    } else {
                        scope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        }
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
            ) {
                Text(if (isLastPage) "Начать" else "Далее")
                if (!isLastPage) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Rounded.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Содержимое страницы: иконка в тонированном круге, заголовок 26sp и текст 15sp. */
@Composable
private fun OnboardingPageContent(page: OnboardingPage, pageIndex: Int) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(104.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = page.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp),
                )
            }
            Spacer(Modifier.height(26.dp))
            Text(
                text = page.title,
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontSize = 26.sp,
                    lineHeight = 32.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = page.body,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            when (pageIndex) {
                1 -> TabsIllustration()
                2 -> PlayerIllustration()
                3 -> WidgetsIllustration()
                else -> Unit
            }
        }
    }
}

/** Мини-иллюстрация вкладок: иконка + подпись, как в нижней навигации. */
@Composable
private fun TabsIllustration() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        OnboardingTabs.forEach { (icon, label) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** Мини-иллюстрация жестов плеера и его главных кнопок. */
@Composable
private fun PlayerIllustration() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GestureHint(Icons.Rounded.KeyboardArrowLeft, "трек", Modifier.weight(1f))
            GestureHint(Icons.Rounded.KeyboardArrowRight, "трек", Modifier.weight(1f))
            GestureHint(Icons.Rounded.KeyboardArrowDown, "закрыть", Modifier.weight(1f))
            GestureHint(Icons.Rounded.KeyboardArrowUp, "очередь", Modifier.weight(1f))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ActionDot(Icons.Rounded.Favorite, "Избранное")
            ActionDot(Icons.Rounded.Bedtime, "Таймер сна")
            ActionDot(Icons.Rounded.QueueMusic, "Очередь")
            ActionDot(Icons.Rounded.Lyrics, "Текст песни")
            ActionDot(Icons.Rounded.Download, "Скачать")
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.TouchApp,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "Двойной тап по обложке — пауза",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Мини-иллюстрация размеров виджетов 2×2, 4×1, 4×2 и 4×4. */
@Composable
private fun WidgetsIllustration() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        WidgetPreview(width = 34.dp, height = 34.dp, label = "2×2", modifier = Modifier.weight(1f, fill = false))
        WidgetPreview(width = 76.dp, height = 26.dp, label = "4×1", modifier = Modifier.weight(1f, fill = false))
        WidgetPreview(width = 76.dp, height = 40.dp, label = "4×2", modifier = Modifier.weight(1f, fill = false))
        WidgetPreview(width = 52.dp, height = 52.dp, label = "4×4", modifier = Modifier.weight(1f, fill = false))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.QueueMusic,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = "Очередь: добавление, порядок и очистка",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Тонированная «кнопка-жест» с подписью под иконкой. */
@Composable
private fun GestureHint(icon: ImageVector, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Круглая иконка действия плеера. */
@Composable
private fun ActionDot(icon: ImageVector, description: String) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** Превью виджета: тональный прямоугольник и подпись с размером. */
@Composable
private fun WidgetPreview(
    width: Dp,
    height: Dp,
    label: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .width(width)
                .height(height)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Точки-индикаторы страниц: активная — вытянутая капсула. */
@Composable
private fun PageDots(pageCount: Int, selectedIndex: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(pageCount) { index ->
            val selected = index == selectedIndex
            val dotWidth by animateDpAsState(
                targetValue = if (selected) 22.dp else 7.dp,
                animationSpec = tween(durationMillis = 220),
            )
            Box(
                modifier = Modifier
                    .padding(horizontal = 3.dp)
                    .height(7.dp)
                    .width(dotWidth)
                    .clip(CircleShape)
                    .background(
                        if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                        },
                    ),
            )
        }
    }
}
