package workflow4s.wio.internal

import cats.effect.IO
import cats.syntax.all._
import workflow4s.wio.Interpreter.{ProceedResponse, SignalResponse, Visitor}
import workflow4s.wio._

object ProceedEvaluator {

  def proceed[StIn, StOut](wio: WIO.States[StIn, StOut], state: StIn, interp: Interpreter): ProceedResponse = {
    val visitor = new ProceedVisitor {
      override def onRunIO[StIn, StOut, Evt, O](wio: WIO.RunIO[StIn, StOut, Evt, O], state: StIn): Option[IO[(StOut, O)]] = {
        (for {
          evt <- wio.buildIO(state)
          _   <- interp.journal.save(evt)(wio.evtHandler.jw)
        } yield wio.evtHandler.handle(state, evt)).some
      }
    }
    visitor
      .dispatch(wio, state)
      .leftMap(_.map(dOutIO => dOutIO.map { case (state, value) => ActiveWorkflow(state, WIO.Noop(), interp, value) }))
      .map(_.map(wfIO => wfIO.map({ wf => ActiveWorkflow(wf.state, wf.wio, interp, wf.value) })))
      .merge
      .map(ProceedResponse.Executed(_))
      .getOrElse(ProceedResponse.Noop())
  }

  abstract class ProceedVisitor extends Visitor {
    type DirectOut[StOut, O]        = Option[IO[(StOut, O)]]
    type FlatMapOut[Err, Out, SOut] = Option[IO[WfAndState.T[Err, Out, SOut]]]

    override def onSignal[Sig, StIn, StOut, Evt, O](wio: WIO.HandleSignal[Sig, StIn, StOut, Evt, O], state: StIn): Option[IO[(StOut, O)]] = None

    def onHandleQuery[Err, Out, StIn, StOut, Qr, QrSt, Resp](
        wio: WIO.HandleQuery[Err, Out, StIn, StOut, Qr, QrSt, Resp],
        state: StIn,
    ): DispatchResult[Err, Out, StOut] =
      dispatch(wio.inner, state) match {
        case Left(value)  => Left(value) // if its direct, we leave the query
        case Right(value) =>
          value
            .map(wfIO =>
              wfIO.map(wf => {
                val preserved: WIO[wf.Err, wf.NextValue, wf.StIn, wf.StOut] = WIO.HandleQuery(wio.queryHandler, wf.wio)
                WfAndState(wf.state, preserved, wf.value)
              }),
            )
            .asRight
      }

    override def onNoop[St, O](wio: WIO.Noop): DirectOut[St, O] = None

    override def onFlatMap[Err, Out1, Out2, S0, S1, S2](wio: WIO.FlatMap[Err, Out1, Out2, S0, S1, S2], state: S0): FlatMapOut[Err, Out1, S2] = {
      val newWfOpt: DispatchResult[Err, Out1, S1] = dispatch(wio.base, state)
      newWfOpt match {
        case Left(dOutOpt)   => dOutOpt.map(dOutIO => dOutIO.map({ case (st, value) => WfAndState(st, wio.getNext(value), value) }))
        case Right(fmOutOpt) =>
          fmOutOpt.map(fmOutIO =>
            fmOutIO.map({ wf =>
              val newWIO: WIO[Err, Out2, wf.StIn, S2] = WIO.FlatMap(wf.wio, (_: wf.NextValue) => wio.getNext(wf.value))
              WfAndState(wf.state, newWIO, wf.value)
            }),
          )
      }
    }

    override def onMap[Err, Out1, Out2, StIn, StOut](
        wio: WIO.Map[Err, Out1, Out2, StIn, StOut],
        state: StIn,
    ): Either[DirectOut[StOut, Out2], FlatMapOut[Err, Out2, StOut]] = {
      dispatch(wio.base, state) match {
        case Left(dOutOpt)   => dOutOpt.map(dOutIO => dOutIO.map({ case (stOut, out) => (stOut, wio.mapValue(out)) })).asLeft
        case Right(fmOutOpt) =>
          fmOutOpt.map(fmOutIO => fmOutIO.map({ wf => WfAndState(wf.state, wf.wio, wio.mapValue(wf.value)) })).asRight
      }
    }

  }

}