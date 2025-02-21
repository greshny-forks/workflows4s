package workflows4s.wio.internal

import workflows4s.wio.{WCEvent, WCState, WorkflowContext}

trait WorkflowEmbedding[Inner <: WorkflowContext, Outer <: WorkflowContext, -Input] { self =>
  def convertEvent(e: WCEvent[Inner]): WCEvent[Outer]
  def unconvertEvent(e: WCEvent[Outer]): Option[WCEvent[Inner]]

  type OutputState[In <: WCState[Inner]] <: WCState[Outer]
  def convertState[In <: WCState[Inner]](innerState: In, input: Input): OutputState[In]
  // TODO can we help with assuring symetry on user side?
  def unconvertState(outerState: WCState[Outer]): Option[WCState[Inner]]

  def contramap[NewInput](f: NewInput => Input): WorkflowEmbedding.Aux[Inner, Outer, OutputState, NewInput] =
    new WorkflowEmbedding[Inner, Outer, NewInput] {
      def convertEvent(e: WCEvent[Inner]): WCEvent[Outer]           = self.convertEvent(e)
      def unconvertEvent(e: WCEvent[Outer]): Option[WCEvent[Inner]] = self.unconvertEvent(e)
      type OutputState[In <: WCState[Inner]] = self.OutputState[In]
      def convertState[In <: WCState[Inner]](innerState: In, input: NewInput): OutputState[In] = self.convertState(innerState, f(input))
      def unconvertState(outerState: WCState[Outer]): Option[WCState[Inner]]                   = self.unconvertState(outerState)
    }
}

object WorkflowEmbedding {
  type Aux[Inner <: WorkflowContext, Outer <: WorkflowContext, OS[_ <: WCState[Inner]] <: WCState[Outer], -Input] =
    WorkflowEmbedding[Inner, Outer, Input] { type OutputState[In <: WCState[Inner]] = OS[In] }

}
