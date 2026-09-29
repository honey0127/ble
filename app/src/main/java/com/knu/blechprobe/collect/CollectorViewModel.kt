package com.knu.blechprobe.collect

import android.app.Application
import androidx.lifecycle.AndroidViewModel

/**
 * 측정기를 Activity 밖에 둔다.
 * 다크모드 자동 전환·글꼴 크기 변경 같은 설정 변경이 일어나면 Activity 는 다시 만들어지지만
 * ViewModel 은 살아남는다. 그래서 10분 측정(T5) 중에 설정이 바뀌어도 CSV 가 닫히지 않는다.
 * onCleared 는 Activity 가 정말 끝날 때(뒤로 가기 등)만 불리고, 설정 변경 때는 불리지 않는다.
 */
class CollectorViewModel(app: Application) : AndroidViewModel(app) {
    val collector = Collector(app)

    override fun onCleared() {
        collector.stop("vm_cleared")
    }
}
