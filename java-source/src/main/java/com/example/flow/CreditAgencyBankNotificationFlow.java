package com.example.flow;

import co.paralleluniverse.fibers.Suspendable;
import com.example.contract.FinanceContract;
import com.example.state.BankAndCreditState;
import com.example.state.FinanceAndBankState;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import net.corda.core.contracts.Command;
import net.corda.core.contracts.ContractState;
import net.corda.core.contracts.StateAndRef;
import net.corda.core.contracts.UniqueIdentifier;
import net.corda.core.flows.*;
import net.corda.core.identity.Party;
import net.corda.core.transactions.SignedTransaction;
import net.corda.core.transactions.TransactionBuilder;
import net.corda.core.utilities.ProgressTracker;

import java.util.List;

import static net.corda.core.contracts.ContractsDSL.requireThat;

public class CreditAgencyBankNotificationFlow
{
    @InitiatingFlow
    @StartableByRPC
    public static class Initiator extends FlowLogic<SignedTransaction>
    {
        private final Party otherParty;
        private final String companyName;
        private final boolean loanEligibleFlag1;
        private final int amount;
        UniqueIdentifier linearId = null;
        String id = null;

        public Initiator(Party otherParty, String companyName, boolean loanEligibleFlag1, int amount)
        {
            this.otherParty = otherParty;
            this.companyName = companyName;
            this.loanEligibleFlag1 = loanEligibleFlag1;
            this.amount = amount;
        }

        private final ProgressTracker.Step VERIFYING_TRANSACTION = new ProgressTracker.Step("Verifying contract constraints.");
        private final ProgressTracker.Step LOAN_ELIGIBILITY_RESPONSE = new ProgressTracker.Step("Response from credit rating agency about loan eligibility and approval");
        private final ProgressTracker.Step SIGNING_TRANSACTION = new ProgressTracker.Step("Signing transaction with our private key.");
        private final ProgressTracker.Step GATHERING_SIGS = new ProgressTracker.Step("Gathering the counterparty's signature.")
        {
            public ProgressTracker childProgressTracker()
            {
                return CollectSignaturesFlow.Companion.tracker();
            }
        };
        private final ProgressTracker.Step FINALISING_TRANSACTION = new ProgressTracker.Step("Obtaining notary signature and recording transaction.")
        {
            public ProgressTracker childProgressTracker()
            {
                return FinalityFlow.Companion.tracker();
            }
        };

        private final ProgressTracker progressTracker = new ProgressTracker(
                VERIFYING_TRANSACTION,
                SIGNING_TRANSACTION,
                LOAN_ELIGIBILITY_RESPONSE,
                GATHERING_SIGS,
                FINALISING_TRANSACTION
        );

        @Suspendable
        @Override
        public SignedTransaction call() throws FlowException
        {
            List<StateAndRef<BankAndCreditState>> inputStateList = null;
            try
            {
                inputStateList = getServiceHub().getVaultService().queryBy(BankAndCreditState.class).getStates();
                if(inputStateList !=null && !(inputStateList.isEmpty()))
                {
                    inputStateList.get(0);
                }
                else
                {
                    throw new IllegalArgumentException("State Cannot be found");
                }
            }
            catch (Exception e)
            {
                e.printStackTrace();
                System.out.println("Exception : "+e);
            }
            final Party notary = getServiceHub().getNetworkMapCache().getNotaryIdentities().get(0);
            progressTracker.setCurrentStep(LOAN_ELIGIBILITY_RESPONSE);
            Party me = getServiceHub().getMyInfo().getLegalIdentities().get(0);
            final StateAndRef<BankAndCreditState> stateAsInput =  inputStateList.get(0);
            linearId = stateAsInput.getState().getData().getLinearId().copy(id,stateAsInput.getState().getData().getLinearId().getId());
            BankAndCreditState bankAndCreditStates = new BankAndCreditState(me,otherParty,false, loanEligibleFlag1, companyName,amount,linearId);
            final Command<FinanceContract.Commands.receiveCreditApproval> receiveCreditApproval = new Command<FinanceContract.Commands.receiveCreditApproval>(new FinanceContract.Commands.receiveCreditApproval(),ImmutableList.of(bankAndCreditStates.getCreditRatingAgency().getOwningKey(),bankAndCreditStates.getStateBank().getOwningKey()));
            final TransactionBuilder txBuilder = new TransactionBuilder(notary)
                     .addInputState(stateAsInput)
                    .addOutputState(bankAndCreditStates,FinanceContract.TEMPLATE_CONTRACT_ID)
                    .addCommand(receiveCreditApproval);
            //step 2
            progressTracker.setCurrentStep(VERIFYING_TRANSACTION);
            txBuilder.verify(getServiceHub());
            //stage 3
            progressTracker.setCurrentStep(SIGNING_TRANSACTION);
            // Sign the transaction.
            final SignedTransaction partSignedTx = getServiceHub().signInitialTransaction(txBuilder);
            //Stage 4
            progressTracker.setCurrentStep(GATHERING_SIGS);
            // Send the state to the counterparty, and receive it back with their signature.
            FlowSession otherPartySession = initiateFlow(otherParty);
            final SignedTransaction fullySignedTx = subFlow(new CollectSignaturesFlow(partSignedTx, ImmutableSet.of(otherPartySession), CollectSignaturesFlow.Companion.tracker()));
            //stage 5
            progressTracker.setCurrentStep(FINALISING_TRANSACTION);
            //Notarise and record the transaction in both party vaults.
            return subFlow(new FinalityFlow(fullySignedTx));
        }
    }

    @InitiatedBy(Initiator.class)
    public static class Acceptor extends FlowLogic<SignedTransaction>
    {
        private final FlowSession otherPartyFlow;
        public Acceptor(FlowSession otherPartyFlow)
        {
            this.otherPartyFlow = otherPartyFlow;
        }

        @Suspendable
        @Override
        public SignedTransaction call() throws FlowException
        {
            class SignTxFlow extends  SignTransactionFlow
            {
                public SignTxFlow(FlowSession otherSideSession, ProgressTracker progressTracker)
                {
                    super(otherSideSession, progressTracker);
                }

                @Override
                protected void checkTransaction(SignedTransaction stx) throws FlowException
                {
                    requireThat(require -> {
                        ContractState output = stx.getTx().getOutputs().get(0).getData();
                        require.using("This must be an FinanceAndBankState transaction.", output instanceof FinanceAndBankState);
                        BankAndCreditState iou = (BankAndCreditState) output;
                        return null;
                    });
                }
            }
            return subFlow(new SignTxFlow(otherPartyFlow, SignTransactionFlow.Companion.tracker()));
        }
    }
}