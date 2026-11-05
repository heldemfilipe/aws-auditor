# Os segredos ficam no SSM Parameter Store (SecureString) e entram na task por referência; NADA disso vai na imagem. O Terraform
# cria só o PARÂMETRO, com um valor de mentira: o valor de verdade se grava fora do Terraform (console ou CLI), e o
# ignore_changes impede que o Terraform o sobrescreva ou o guarde no state.
locals {
  segredos = {
    telegram_token   = "TELEGRAM_BOT_TOKEN"
    telegram_chat_id = "TELEGRAM_CHAT_ID"
    api_key          = "AUDITOR_API_KEY"
  }
}

resource "aws_ssm_parameter" "segredo" {
  for_each = local.segredos

  name        = "/${var.nome}/${each.key}"
  description = "Preencher fora do Terraform: ${each.value}"
  type        = "SecureString"
  value       = "PREENCHER-FORA-DO-TERRAFORM"

  lifecycle {
    ignore_changes = [value]
  }
}
