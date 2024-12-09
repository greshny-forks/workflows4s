package workflows4s.example.docs

import Context.WIO
import cats.effect.IO
import workflows4s.wio.SignalDef

object HandleSignalExample {

  // start_withoutError
  val MySignal = SignalDef[MyRequest, MyResponse]()

  val doThings: WIO[MyState, Nothing, MyState] =
    WIO
      .handleSignal(MySignal)
      .using[MyState]
      .withSideEffects((state, request) => IO(MyEvent()))
      .handleEvent((state, event) => state)
      .produceResponse((state, event) => MyResponse())
      .autoNamed()
  // end_withoutError

}