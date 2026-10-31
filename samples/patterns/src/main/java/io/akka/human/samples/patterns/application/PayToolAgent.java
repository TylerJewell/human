package io.akka.human.samples.patterns.application;

import akka.javasdk.agent.Agent;
import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.FunctionTool;

/**
 * An agent with one tool, {@code pay}, which the "payment approval" guardrail in application.conf holds
 * until a person approves the call. It exists so the guardrail runs inside a model's tool loop; the
 * tool only reports the payment it was asked for.
 */
@Component(id = "pay-tool-agent", description = "Pays a supplier invoice through the pay tool.")
public class PayToolAgent extends Agent {

  public Effect<String> run(String request) {
    return effects()
        .systemMessage("Pay the invoice the user names by calling the pay tool, then report the result.")
        .userMessage(request)
        .thenReply();
  }

  @FunctionTool(description = "Pay a supplier invoice")
  private String pay(String supplier, int amount) {
    return "paid " + amount + " to " + supplier;
  }
}
